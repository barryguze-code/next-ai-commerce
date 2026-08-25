package com.nextaicommerce.platform.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WorkspaceAccessRepository {
    public record AccountView(UUID id, String name, String initials, String slug, String status, int users, int connections) {}
    public record AccountOption(UUID id, String name, String initials, List<ConnectionView> stores) {}
    public record ConnectionView(UUID id, UUID tenantId, String tenantName, String name, String channel,
        String sellerId, String marketplaceId, String marketplaceLabel, String countryFlag,
        String status, Instant lastCheckedAt, boolean credentialsStored) {}
    public record ConnectionIdentity(UUID id, String channel) {}
    public record MemberView(UUID id, String displayName, String email, String role, String status, int stores) {}

    private final JdbcTemplate jdbc;

    public WorkspaceAccessRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public List<AccountView> listAllAccounts() {
        List<AccountView> result = new ArrayList<>();
        for (var tenant : jdbc.query("SELECT id, display_name, slug, status FROM tenants WHERE status = 'ACTIVE' ORDER BY display_name",
                (rs, row) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)})) {
            UUID tenantId = (UUID) tenant[0];
            setTenant(tenantId);
            Integer users = jdbc.queryForObject("SELECT count(*) FROM tenant_memberships WHERE tenant_id = ?", Integer.class, tenantId);
            Integer connections = jdbc.queryForObject("SELECT count(*) FROM marketplace_connections WHERE tenant_id = ?", Integer.class, tenantId);
            String name = (String) tenant[1];
            result.add(new AccountView(tenantId, name, accountInitials(name), (String) tenant[2], (String) tenant[3],
                users == null ? 0 : users, connections == null ? 0 : connections));
        }
        return result;
    }

    private static String accountInitials(String name) {
        if (name == null || name.isBlank()) return "BA";
        String[] words = name.trim().split("\\s+");
        if (words.length == 1) return words[0].substring(0, Math.min(2, words[0].length())).toUpperCase(Locale.ROOT);
        return (words[0].substring(0, 1) + words[1].substring(0, 1)).toUpperCase(Locale.ROOT);
    }

    @Transactional
    public void addAccount(String displayName) {
        if (displayName == null || displayName.isBlank())
            throw new IllegalArgumentException("Enter an account name.");
        String cleanName = displayName.trim().replaceAll("\\s+", " ");
        if (cleanName.length() > 160)
            throw new IllegalArgumentException("Account name must be 160 characters or fewer.");
        String slug = Normalizer.normalize(cleanName, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isBlank()) throw new IllegalArgumentException("Use letters or numbers in the account name.");
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE slug=?", Integer.class, slug);
        if (existing != null && existing > 0)
            throw new IllegalArgumentException("An account with this name already exists.");
        jdbc.update("INSERT INTO tenants (slug, display_name, status) VALUES (?, ?, 'ACTIVE')", slug, cleanName);
    }

    @Transactional(readOnly = true)
    public List<AccountView> listAvailableAccounts(String email, boolean superAdmin) {
        if (superAdmin) return listAllAccounts();
        List<AccountView> result = new ArrayList<>();
        for (AccountView account : listAllAccounts()) {
            setTenant(account.id());
            Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM tenant_memberships m JOIN app_users u ON u.id = m.user_id
                WHERE m.tenant_id = ? AND lower(u.email) = lower(?)
                """, Integer.class, account.id(), email);
            if (count != null && count > 0) result.add(account);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<AccountOption> listAccountOptions(String email, boolean superAdmin) {
        return listAvailableAccounts(email, superAdmin).stream()
            .map(account -> new AccountOption(account.id(), account.name(), account.initials(),
                visibleConnections(account, email, superAdmin)))
            .toList();
    }

    private List<ConnectionView> visibleConnections(AccountView account, String email, boolean superAdmin) {
        List<ConnectionView> all = listConnections(account.id(), account.name());
        if (superAdmin) return all;
        setTenant(account.id());
        String role = jdbc.query("""
            SELECT m.role FROM tenant_memberships m JOIN app_users u ON u.id=m.user_id
            WHERE m.tenant_id=? AND lower(u.email)=lower(?)
            """, rs -> rs.next() ? rs.getString(1) : "", account.id(), email);
        if ("OWNER".equals(role) || "ADMIN".equals(role)) return all;
        Set<UUID> allowed = Set.copyOf(jdbc.query("""
            SELECT msa.marketplace_connection_id FROM membership_store_access msa
            JOIN app_users u ON u.id=msa.user_id
            WHERE msa.tenant_id=? AND lower(u.email)=lower(?)
            """, (rs, row) -> rs.getObject(1, UUID.class), account.id(), email));
        return all.stream().filter(connection -> allowed.contains(connection.id())).toList();
    }

    @Transactional(readOnly = true)
    public boolean canAccessAccount(UUID tenantId, String email, boolean superAdmin) {
        if (superAdmin) return jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id=? AND status='ACTIVE'", Integer.class, tenantId) == 1;
        setTenant(tenantId);
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM tenant_memberships m JOIN app_users u ON u.id=m.user_id
            WHERE m.tenant_id=? AND lower(u.email)=lower(?)
            """, Integer.class, tenantId, email);
        return count != null && count == 1;
    }

    @Transactional(readOnly = true)
    public boolean connectionBelongsToAccount(UUID tenantId, UUID connectionId) {
        setTenant(tenantId);
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM marketplace_connections WHERE tenant_id=? AND id=?",
            Integer.class, tenantId, connectionId);
        return count != null && count == 1;
    }

    @Transactional(readOnly = true)
    public List<ConnectionView> listConnections(UUID tenantId, String tenantName) {
        setTenant(tenantId);
        return jdbc.query("""
                SELECT id, tenant_id,
                       CASE seller_identifier
                           WHEN 'A1NDXA1M2438J4' THEN 'Ibcore'
                           WHEN 'A1PM59EP9QEK4B' THEN 'Karaca'
                           ELSE display_name
                       END AS display_name,
                       channel, seller_identifier,
                       marketplace_identifier, status, last_synced_at,
                       EXISTS (SELECT 1 FROM marketplace_connection_credentials credentials
                               WHERE credentials.tenant_id = marketplace_connections.tenant_id
                                 AND credentials.marketplace_connection_id = marketplace_connections.id)
                           AS credentials_stored
                FROM marketplace_connections WHERE tenant_id = ? ORDER BY display_name
                """, (rs, row) -> new ConnectionView(rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class), tenantName, rs.getString("display_name"),
                    rs.getString("channel"), rs.getString("seller_identifier"),
                    rs.getString("marketplace_identifier"), marketplaceLabel(rs.getString("marketplace_identifier")),
                    countryFlag(rs.getString("marketplace_identifier")), rs.getString("status"),
                    rs.getTimestamp("last_synced_at") == null ? null : rs.getTimestamp("last_synced_at").toInstant(),
                    rs.getBoolean("credentials_stored")),
                tenantId);
    }

    @Transactional(readOnly = true)
    public ConnectionIdentity findConnection(UUID tenantId, UUID connectionId) {
        setTenant(tenantId);
        return jdbc.query("SELECT id, channel FROM marketplace_connections WHERE tenant_id=? AND id=?",
            (rs, row) -> new ConnectionIdentity(rs.getObject("id", UUID.class), rs.getString("channel")),
            tenantId, connectionId).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Marketplace connection was not found."));
    }

    @Transactional
    public void saveCredentials(UUID tenantId, UUID connectionId, byte[] payload, byte[] nonce) {
        setTenant(tenantId);
        jdbc.update("""
            INSERT INTO marketplace_connection_credentials
                (tenant_id, marketplace_connection_id, encrypted_payload, encryption_nonce)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant_id, marketplace_connection_id) DO UPDATE
                SET encrypted_payload=EXCLUDED.encrypted_payload,
                    encryption_nonce=EXCLUDED.encryption_nonce, key_version=1
            """, tenantId, connectionId, payload, nonce);
        jdbc.update("""
            UPDATE marketplace_connections
            SET credential_secret_ref=?, status='ACTIVE', last_synced_at=now()
            WHERE tenant_id=? AND id=?
            """, "database-encrypted://marketplace-connection/" + connectionId, tenantId, connectionId);
    }

    @Transactional
    public void addConnection(UUID tenantId, String channel, String displayName,
            String sellerIdentifier, String marketplaceIdentifier) {
        if (!List.of("AMAZON", "WALMART").contains(channel))
            throw new IllegalArgumentException("Choose a supported marketplace.");
        boolean marketplaceMatches = "AMAZON".equals(channel)
            ? List.of("ATVPDKIKX0DER", "A1F83G8C2ARO7P", "A2EUQ1WTGCTBG2").contains(marketplaceIdentifier)
            : "Walmart-US".equals(marketplaceIdentifier);
        if (!marketplaceMatches) throw new IllegalArgumentException("Choose a marketplace that matches the channel.");
        if (displayName == null || displayName.isBlank())
            throw new IllegalArgumentException("Enter a store name.");
        if ("AMAZON".equals(channel) && (sellerIdentifier == null || sellerIdentifier.isBlank()))
            throw new IllegalArgumentException("Enter the Amazon Seller ID.");
        String storedIdentifier = "AMAZON".equals(channel)
            ? sellerIdentifier.trim()
            : "WALMART-" + UUID.randomUUID();
        setTenant(tenantId);
        jdbc.update("""
            INSERT INTO marketplace_connections
                (tenant_id, channel, seller_identifier, marketplace_identifier,
                 credential_secret_ref, status, display_name)
            VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
            """, tenantId, channel, storedIdentifier, marketplaceIdentifier,
            "pending://marketplace-connection", displayName.trim());
    }

    @Transactional(readOnly = true)
    public List<MemberView> listMembers(UUID tenantId) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT u.id, u.display_name, u.email, m.role, u.status,
                   (SELECT count(*) FROM membership_store_access msa
                    WHERE msa.tenant_id = m.tenant_id AND msa.user_id = m.user_id) AS stores
            FROM tenant_memberships m
            JOIN app_users u ON u.id = m.user_id
            WHERE m.tenant_id = ?
            ORDER BY lower(u.display_name), lower(u.email)
            """, (rs, row) -> new MemberView(
                rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("email"),
                rs.getString("role"), rs.getString("status"), rs.getInt("stores")), tenantId);
    }

    @Transactional(readOnly = true)
    public int pendingInvitationCount(UUID tenantId) {
        setTenant(tenantId);
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM tenant_invitations WHERE tenant_id = ? AND status = 'PENDING'",
            Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    private void setTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private static String marketplaceLabel(String id) {
        return switch (id) {
            case "ATVPDKIKX0DER" -> "Amazon US";
            case "A1F83G8C2ARO7P" -> "Amazon UK";
            case "A2EUQ1WTGCTBG2" -> "Amazon Canada";
            case "Walmart-US" -> "Walmart US";
            default -> id;
        };
    }

    private static String countryFlag(String id) {
        return switch (id) {
            case "A1F83G8C2ARO7P" -> "🇬🇧";
            case "A2EUQ1WTGCTBG2" -> "🇨🇦";
            default -> "🇺🇸";
        };
    }
}
