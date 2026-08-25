package com.nextaicommerce.platform.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivationService {
    private record Activation(UUID id, UUID userId, Instant expiresAt) {}
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    ActivationService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean isValid(String token) {
        if (token == null || token.isBlank()) return false;
        return !find(token).isEmpty();
    }

    @Transactional
    public void activate(String token, String password, String confirmation) {
        if (password == null || password.length() < 12)
            throw new IllegalArgumentException("Use at least 12 characters");
        if (!password.equals(confirmation)) throw new IllegalArgumentException("Passwords do not match");
        List<Activation> matches = find(token);
        if (matches.size() != 1) throw new IllegalArgumentException("Activation link is invalid or expired");
        Activation activation = matches.getFirst();
        jdbc.update("UPDATE app_users SET password_hash = ?, status = 'ACTIVE' WHERE id = ?",
            passwordEncoder.encode(password), activation.userId());
        jdbc.update("UPDATE user_activation_tokens SET status = 'USED', used_at = now() WHERE id = ? AND status = 'PENDING'",
            activation.id());
    }

    private List<Activation> find(String token) {
        return jdbc.query("""
            SELECT id, user_id, expires_at FROM user_activation_tokens
            WHERE token_hash = ? AND status = 'PENDING' AND expires_at > now()
            """, (rs, row) -> new Activation(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getTimestamp("expires_at").toInstant()), hash(token));
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
