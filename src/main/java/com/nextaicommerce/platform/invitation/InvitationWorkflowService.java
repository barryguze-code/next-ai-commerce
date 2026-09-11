package com.nextaicommerce.platform.invitation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.nextaicommerce.platform.config.ActivationService;

@Service
public class InvitationWorkflowService {
    private final JdbcTemplate jdbc;
    private final InvitationService invitations;
    private final InvitationMailer mailer;
    private final String publicUrl;

    InvitationWorkflowService(JdbcTemplate jdbc, InvitationService invitations, InvitationMailer mailer,
            @Value("${app.public-url}") String publicUrl) {
        this.jdbc = jdbc;
        this.invitations = invitations;
        this.mailer = mailer;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    @Transactional
    public void invite(UUID tenantId, String accountName, String actorEmail, String email,
            InvitationRole role, Set<UUID> storeIds) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        UUID actorId = userId(actorEmail);
        if (actorId == null) throw new InvitationException("The signed-in administrator was not found.");
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        expireOldInvitations(tenantId, normalizedEmail);
        Integer pending = jdbc.queryForObject(
            "SELECT count(*) FROM tenant_invitations WHERE tenant_id=? AND email=? AND status='PENDING'",
            Integer.class, tenantId, normalizedEmail);
        if (pending != null && pending > 0)
            throw new InvitationException("A pending invitation already exists for this email address.");
        var created = invitations.create(tenantId, actorId, normalizedEmail, role, storeIds,
            "Team invitation from Users & Access");

        List<UserState> users = jdbc.query("SELECT id, status FROM app_users WHERE lower(email)=?",
            (rs, row) -> new UserState(rs.getObject("id", UUID.class), rs.getString("status")), normalizedEmail);
        boolean existingActiveUser = !users.isEmpty() && "ACTIVE".equals(users.getFirst().status());
        String url;
        if (existingActiveUser) {
            url = publicUrl + "/invitation/accept?tenantId=" + tenantId + "&token=" + created.token();
        } else {
            UUID userId = users.isEmpty() ? createInvitedUser(normalizedEmail, actorId) : users.getFirst().id();
            jdbc.update("DELETE FROM user_activation_tokens WHERE user_id=? AND status='PENDING'", userId);
            jdbc.update("INSERT INTO user_activation_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
                userId, ActivationService.hash(created.token()), Timestamp.from(created.expiresAt()));
            url = publicUrl + "/activate?tenantId=" + tenantId + "&token=" + created.token();
        }
        String inviterName = jdbc.queryForObject("SELECT display_name FROM app_users WHERE id=?",
            String.class, actorId);
        mailer.send(normalizedEmail, inviterName, accountName, roleLabel(role), url);
    }

    @Transactional
    public void resend(UUID tenantId, String accountName, String actorEmail, UUID invitationId) {
        UUID actorId = requireActor(tenantId, actorEmail);
        InvitationSnapshot previous = jdbc.query("""
            SELECT email, role FROM tenant_invitations
            WHERE tenant_id=? AND id=? AND status IN ('PENDING','EXPIRED') FOR UPDATE
            """, rs -> rs.next() ? new InvitationSnapshot(rs.getString("email"),
                InvitationRole.valueOf(rs.getString("role"))) : null, tenantId, invitationId);
        if (previous == null) throw new InvitationException("This invitation is no longer available to resend.");
        Set<UUID> stores = Set.copyOf(jdbc.query("""
            SELECT marketplace_connection_id FROM invitation_store_access
            WHERE tenant_id=? AND invitation_id=?
            """, (rs, row) -> rs.getObject(1, UUID.class), tenantId, invitationId));
        jdbc.update("""
            UPDATE tenant_invitations SET status='REVOKED', revoked_at=now()
            WHERE tenant_id=? AND id=? AND status IN ('PENDING','EXPIRED')
            """, tenantId, invitationId);
        var created = invitations.create(tenantId, actorId, previous.email(), previous.role(), stores,
            "Invitation resent from Users & Access");
        sendCreatedInvitation(tenantId, accountName, actorId, previous.email(), previous.role(), created);
    }

    @Transactional
    public void revoke(UUID tenantId, String actorEmail, UUID invitationId) {
        UUID actorId = requireActor(tenantId, actorEmail);
        int changed = jdbc.update("""
            UPDATE tenant_invitations SET status='REVOKED', revoked_at=now()
            WHERE tenant_id=? AND id=? AND status IN ('PENDING','EXPIRED')
            """, tenantId, invitationId);
        if (changed != 1) throw new InvitationException("This invitation is no longer pending.");
        jdbc.update("""
            UPDATE user_activation_tokens SET status='REVOKED'
            WHERE status='PENDING' AND token_hash=(SELECT token_hash FROM tenant_invitations WHERE tenant_id=? AND id=?)
            """, tenantId, invitationId);
        jdbc.update("""
            INSERT INTO audit_events (tenant_id,actor_user_id,action,target_type,target_id,details)
            VALUES (?,?,'INVITATION_REVOKED','TENANT_INVITATION',?,jsonb_build_object('source','Users & Access'))
            """, tenantId, actorId, invitationId.toString());
    }

    @Transactional
    public void acceptExisting(UUID tenantId, String token, String signedInEmail) {
        UUID userId = userId(signedInEmail);
        if (userId == null) throw new InvitationException("The signed-in user was not found.");
        invitations.accept(tenantId, userId, signedInEmail, token);
    }

    public String accountName(UUID tenantId) {
        return jdbc.query("SELECT display_name FROM tenants WHERE id=?",
            rs -> rs.next() ? rs.getString(1) : "Business account", tenantId);
    }

    private UUID userId(String email) {
        return jdbc.query("SELECT id FROM app_users WHERE lower(email)=lower(?)",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null, email);
    }

    private UUID requireActor(UUID tenantId, String actorEmail) {
        UUID actorId = userId(actorEmail);
        if (actorId == null) throw new InvitationException("The signed-in administrator was not found.");
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        Boolean allowed = jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM platform_administrators WHERE user_id=? AND active)
                OR EXISTS(SELECT 1 FROM tenant_memberships WHERE tenant_id=? AND user_id=? AND role IN ('OWNER','ADMIN'))
            """, Boolean.class, actorId, tenantId, actorId);
        if (!Boolean.TRUE.equals(allowed)) throw new InvitationException("Administrator access is required.");
        return actorId;
    }

    private void expireOldInvitations(UUID tenantId, String email) {
        jdbc.update("""
            UPDATE tenant_invitations SET status='EXPIRED'
            WHERE tenant_id=? AND email=? AND status='PENDING' AND expires_at<=now()
            """, tenantId, email);
    }

    private void sendCreatedInvitation(UUID tenantId, String accountName, UUID actorId, String email,
            InvitationRole role, InvitationService.CreatedInvitation created) {
        List<UserState> users = jdbc.query("SELECT id, status FROM app_users WHERE lower(email)=?",
            (rs, row) -> new UserState(rs.getObject("id", UUID.class), rs.getString("status")), email);
        boolean active = !users.isEmpty() && "ACTIVE".equals(users.getFirst().status());
        String url;
        if (active) {
            url = publicUrl + "/invitation/accept?tenantId=" + tenantId + "&token=" + created.token();
        } else {
            UUID userId = users.isEmpty() ? createInvitedUser(email, actorId) : users.getFirst().id();
            jdbc.update("DELETE FROM user_activation_tokens WHERE user_id=? AND status='PENDING'", userId);
            jdbc.update("INSERT INTO user_activation_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
                userId, ActivationService.hash(created.token()), Timestamp.from(created.expiresAt()));
            url = publicUrl + "/activate?tenantId=" + tenantId + "&token=" + created.token();
        }
        String inviterName = jdbc.queryForObject("SELECT display_name FROM app_users WHERE id=?", String.class, actorId);
        mailer.send(email, inviterName, accountName, roleLabel(role), url);
    }

    private UUID createInvitedUser(String email, UUID actorId) {
        String localPart = email.substring(0, email.indexOf('@')).replaceAll("[._-]+", " ").trim();
        String displayName = localPart.isBlank() ? "Invited user" : localPart;
        return jdbc.queryForObject("""
            INSERT INTO app_users (email, display_name, status, created_by, updated_by)
            VALUES (?, ?, 'INVITED', ?, ?) RETURNING id
            """, UUID.class, email, displayName, actorId, actorId);
    }

    private static String roleLabel(InvitationRole role) {
        return switch (role) {
            case ADMIN -> "Administrator";
            case OPERATOR -> "Operator";
            case VIEWER -> "Viewer";
        };
    }

    private record UserState(UUID id, String status) {}
    private record InvitationSnapshot(String email, InvitationRole role) {}
}
