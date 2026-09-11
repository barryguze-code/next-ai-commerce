package com.nextaicommerce.platform.invitation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInvitationRepository implements InvitationRepository {
    private final JdbcTemplate jdbc;

    public JdbcInvitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ActorAccess findActorAccess(UUID tenantId, UUID actorUserId) {
        setTenant(tenantId);
        Boolean superAdmin = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM platform_administrators WHERE user_id = ? AND active)",
            Boolean.class, actorUserId);
        if (Boolean.TRUE.equals(superAdmin)) return ActorAccess.SUPER_ADMIN;

        return jdbc.query(
            "SELECT role FROM tenant_memberships WHERE tenant_id = ? AND user_id = ?",
            resultSet -> resultSet.next() ? toActorAccess(resultSet.getString("role")) : ActorAccess.NONE,
            tenantId, actorUserId);
    }

    @Override
    public UUID create(
        UUID tenantId,
        String email,
        InvitationRole role,
        String tokenHash,
        UUID invitedBy,
        boolean invitedBySuperAdmin,
        String reason,
        Instant expiresAt,
        Set<UUID> marketplaceConnectionIds) {

        setTenant(tenantId);
        UUID invitationId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO tenant_invitations
                (id, tenant_id, email, role, token_hash, invited_by, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, invitationId, tenantId, email, role.name(), tokenHash, invitedBy, Timestamp.from(expiresAt));

        for (UUID storeId : marketplaceConnectionIds) {
            jdbc.update("""
                INSERT INTO invitation_store_access
                    (invitation_id, tenant_id, marketplace_connection_id)
                VALUES (?, ?, ?)
                """, invitationId, tenantId, storeId);
        }

        jdbc.update("""
            INSERT INTO audit_events
                (tenant_id, actor_user_id, actor_is_super_admin, action, target_type, target_id, reason, details)
            VALUES (?, ?, ?, 'USER_INVITED', 'TENANT_INVITATION', ?, ?,
                jsonb_build_object('email', ?, 'role', ?))
            """, tenantId, invitedBy, invitedBySuperAdmin, invitationId.toString(), reason, email, role.name());
        return invitationId;
    }

    @Override
    public Optional<StoredInvitation> lockByTokenHash(UUID tenantId, String tokenHash) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT id, tenant_id, email, role, status, expires_at
            FROM tenant_invitations
            WHERE tenant_id = ? AND token_hash = ?
            FOR UPDATE
            """, resultSet -> resultSet.next() ? Optional.of(mapInvitation(resultSet)) : Optional.empty(),
            tenantId, tokenHash);
    }

    @Override
    public void accept(StoredInvitation invitation, UUID acceptingUserId, UUID actorUserId) {
        setTenant(invitation.tenantId());
        jdbc.update("""
            INSERT INTO tenant_memberships
                (tenant_id, user_id, role, created_by, updated_by)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, user_id) DO UPDATE
                SET role = EXCLUDED.role, updated_by = EXCLUDED.updated_by
            """, invitation.tenantId(), acceptingUserId, invitation.role().name(), actorUserId, actorUserId);

        jdbc.update("""
            INSERT INTO membership_store_access
                (tenant_id, user_id, marketplace_connection_id, granted_by)
            SELECT tenant_id, ?, marketplace_connection_id, ?
            FROM invitation_store_access
            WHERE invitation_id = ? AND tenant_id = ?
            ON CONFLICT DO NOTHING
            """, acceptingUserId, actorUserId, invitation.id(), invitation.tenantId());

        jdbc.update("""
            UPDATE tenant_invitations
            SET status = 'ACCEPTED', accepted_by = ?, accepted_at = now()
            WHERE id = ? AND tenant_id = ? AND status = 'PENDING'
            """, acceptingUserId, invitation.id(), invitation.tenantId());

        jdbc.update("""
            INSERT INTO audit_events
                (tenant_id, actor_user_id, action, target_type, target_id, details)
            VALUES (?, ?, 'INVITATION_ACCEPTED', 'TENANT_MEMBERSHIP', ?, jsonb_build_object('invitationId', ?))
            """, invitation.tenantId(), actorUserId, acceptingUserId.toString(), invitation.id().toString());
    }

    private void setTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private static ActorAccess toActorAccess(String role) {
        return switch (role) {
            case "OWNER" -> ActorAccess.OWNER;
            case "ADMIN" -> ActorAccess.ADMIN;
            default -> ActorAccess.OTHER;
        };
    }

    private static StoredInvitation mapInvitation(ResultSet resultSet) throws SQLException {
        return new StoredInvitation(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("tenant_id", UUID.class),
            resultSet.getString("email"),
            InvitationRole.valueOf(resultSet.getString("role")),
            InvitationStatus.valueOf(resultSet.getString("status")),
            resultSet.getTimestamp("expires_at").toInstant());
    }
}
