package com.nextaicommerce.platform.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvitationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-22T20:00:00Z");

    private FakeInvitationRepository repository;
    private InvitationService service;

    @BeforeEach
    void setUp() {
        repository = new FakeInvitationRepository();
        service = new InvitationService(repository, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
    }

    @Test
    void ownerCanCreateInvitationAndOnlyHashIsStored() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        repository.actorAccess = InvitationRepository.ActorAccess.OWNER;

        InvitationService.CreatedInvitation created = service.create(
            tenantId, actorId, " New.User@Example.com ", InvitationRole.OPERATOR,
            Set.of(storeId), null);

        assertThat(created.invitationId()).isEqualTo(repository.createdId);
        assertThat(created.token()).hasSize(43);
        assertThat(repository.createdEmail).isEqualTo("new.user@example.com");
        assertThat(repository.createdTokenHash).hasSize(64).doesNotContain(created.token());
        assertThat(repository.createdStoreIds).containsExactly(storeId);
        assertThat(repository.createdExpiresAt).isEqualTo(NOW.plusSeconds(7 * 24 * 60 * 60));
        assertThat(repository.createdBySuperAdmin).isFalse();
    }

    @Test
    void operatorCannotInviteUsers() {
        repository.actorAccess = InvitationRepository.ActorAccess.OTHER;

        assertThatThrownBy(() -> service.create(
            UUID.randomUUID(), UUID.randomUUID(), "user@example.com",
            InvitationRole.VIEWER, Set.of(), null))
            .isInstanceOf(InvitationException.class)
            .hasMessageContaining("Admin");
        assertThat(repository.createCalled).isFalse();
    }

    @Test
    void superAdminMustProvideSupportReason() {
        repository.actorAccess = InvitationRepository.ActorAccess.SUPER_ADMIN;

        assertThatThrownBy(() -> service.create(
            UUID.randomUUID(), UUID.randomUUID(), "user@example.com",
            InvitationRole.ADMIN, Set.of(), " "))
            .isInstanceOf(InvitationException.class)
            .hasMessageContaining("reason");
        assertThat(repository.createCalled).isFalse();
    }

    @Test
    void matchingUserCanAcceptPendingInvitation() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        repository.storedInvitation = new InvitationRepository.StoredInvitation(
            UUID.randomUUID(), tenantId, "user@example.com", InvitationRole.VIEWER,
            InvitationStatus.PENDING, NOW.plusSeconds(60));

        service.accept(tenantId, userId, "User@Example.com", "single-use-token");

        assertThat(repository.acceptedInvitation).isEqualTo(repository.storedInvitation);
        assertThat(repository.acceptedUserId).isEqualTo(userId);
    }

    @Test
    void expiredInvitationCannotBeAccepted() {
        UUID tenantId = UUID.randomUUID();
        repository.storedInvitation = new InvitationRepository.StoredInvitation(
            UUID.randomUUID(), tenantId, "user@example.com", InvitationRole.VIEWER,
            InvitationStatus.PENDING, NOW);

        assertThatThrownBy(() -> service.accept(
            tenantId, UUID.randomUUID(), "user@example.com", "expired-token"))
            .isInstanceOf(InvitationException.class)
            .hasMessageContaining("expired");
        assertThat(repository.acceptedInvitation).isNull();
    }

    private static final class FakeInvitationRepository implements InvitationRepository {
        private ActorAccess actorAccess = ActorAccess.NONE;
        private final UUID createdId = UUID.randomUUID();
        private boolean createCalled;
        private String createdEmail;
        private String createdTokenHash;
        private boolean createdBySuperAdmin;
        private Instant createdExpiresAt;
        private Set<UUID> createdStoreIds;
        private StoredInvitation storedInvitation;
        private StoredInvitation acceptedInvitation;
        private UUID acceptedUserId;

        @Override
        public ActorAccess findActorAccess(UUID tenantId, UUID actorUserId) {
            return actorAccess;
        }

        @Override
        public UUID create(
            UUID tenantId, String email, InvitationRole role, String tokenHash, UUID invitedBy,
            boolean invitedBySuperAdmin, String reason, Instant expiresAt,
            Set<UUID> marketplaceConnectionIds) {

            createCalled = true;
            createdEmail = email;
            createdTokenHash = tokenHash;
            createdBySuperAdmin = invitedBySuperAdmin;
            createdExpiresAt = expiresAt;
            createdStoreIds = marketplaceConnectionIds;
            return createdId;
        }

        @Override
        public Optional<StoredInvitation> lockByTokenHash(UUID tenantId, String tokenHash) {
            return Optional.ofNullable(storedInvitation);
        }

        @Override
        public void accept(StoredInvitation invitation, UUID acceptingUserId, UUID actorUserId) {
            acceptedInvitation = invitation;
            acceptedUserId = acceptingUserId;
        }
    }
}
