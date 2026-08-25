package com.nextaicommerce.platform.invitation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {
    public record CreatedInvitation(UUID invitationId, String token, Instant expiresAt) {}

    private static final Duration DEFAULT_EXPIRATION = Duration.ofDays(7);

    private final InvitationRepository repository;
    private final Clock clock;
    private final SecureRandom secureRandom;

    @Autowired
    public InvitationService(InvitationRepository repository) {
        this(repository, Clock.systemUTC(), new SecureRandom());
    }

    InvitationService(InvitationRepository repository, Clock clock, SecureRandom secureRandom) {
        this.repository = repository;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    @Transactional
    public CreatedInvitation create(
        UUID tenantId,
        UUID actorUserId,
        String email,
        InvitationRole role,
        Set<UUID> marketplaceConnectionIds,
        String superAdminReason) {

        InvitationRepository.ActorAccess actorAccess = requireTenantAdministrator(tenantId, actorUserId);
        if (role == null) throw new InvitationException("Invitation role is required");
        boolean superAdmin = actorAccess == InvitationRepository.ActorAccess.SUPER_ADMIN;
        String reason = superAdmin
            ? requireText(superAdminReason, "Super Admin reason is required").trim()
            : null;
        String normalizedEmail = normalizeEmail(email);
        Set<UUID> storeIds = marketplaceConnectionIds == null ? Set.of() : Set.copyOf(marketplaceConnectionIds);
        Instant expiresAt = clock.instant().plus(DEFAULT_EXPIRATION);
        String token = newToken();
        UUID invitationId = repository.create(
            tenantId, normalizedEmail, role, hash(token), actorUserId, superAdmin, reason, expiresAt, storeIds);
        return new CreatedInvitation(invitationId, token, expiresAt);
    }

    @Transactional
    public void accept(UUID tenantId, UUID acceptingUserId, String acceptingEmail, String token) {
        String tokenHash = hash(requireText(token, "Invitation token is required"));
        InvitationRepository.StoredInvitation invitation = repository.lockByTokenHash(tenantId, tokenHash)
            .orElseThrow(() -> new InvitationException("Invitation is invalid"));

        if (invitation.status() != InvitationStatus.PENDING) {
            throw new InvitationException("Invitation is no longer pending");
        }
        if (!invitation.expiresAt().isAfter(clock.instant())) {
            throw new InvitationException("Invitation has expired");
        }
        if (!invitation.email().equals(normalizeEmail(acceptingEmail))) {
            throw new InvitationException("Invitation belongs to a different email address");
        }

        repository.accept(invitation, acceptingUserId, acceptingUserId);
    }

    private InvitationRepository.ActorAccess requireTenantAdministrator(UUID tenantId, UUID actorUserId) {
        InvitationRepository.ActorAccess access = repository.findActorAccess(tenantId, actorUserId);
        if (access != InvitationRepository.ActorAccess.SUPER_ADMIN
            && access != InvitationRepository.ActorAccess.OWNER
            && access != InvitationRepository.ActorAccess.ADMIN) {
            throw new InvitationException("Owner, Admin, or Super Admin access is required");
        }
        return access;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalizeEmail(String email) {
        String normalized = requireText(email, "Email is required").trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains("@") || normalized.length() > 320) {
            throw new InvitationException("Email is invalid");
        }
        return normalized;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new InvitationException(message);
        return value;
    }
}
