package com.nextaicommerce.platform.invitation;

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
                userId, ActivationService.hash(created.token()), created.expiresAt());
            url = publicUrl + "/activate?tenantId=" + tenantId + "&token=" + created.token();
        }
        mailer.send(normalizedEmail, accountName, roleLabel(role), url);
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
}
