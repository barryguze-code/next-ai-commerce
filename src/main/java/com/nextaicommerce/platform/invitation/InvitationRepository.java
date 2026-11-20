package com.nextaicommerce.platform.invitation;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface InvitationRepository {
    enum ActorAccess { SUPER_ADMIN, OWNER, ADMIN, OTHER, NONE }

    record StoredInvitation(
        UUID id,
        UUID tenantId,
        String email,
        InvitationRole role,
        InvitationStatus status,
        Instant expiresAt) {}

    ActorAccess findActorAccess(UUID tenantId, UUID actorUserId);

    UUID create(
        UUID tenantId,
        String email,
        InvitationRole role,
        String tokenHash,
        UUID invitedBy,
        boolean invitedBySuperAdmin,
        String reason,
        Instant expiresAt,
        Set<UUID> marketplaceConnectionIds);

    Optional<StoredInvitation> lockByTokenHash(UUID tenantId, String tokenHash);

    void accept(StoredInvitation invitation, UUID acceptingUserId, UUID actorUserId);
}
