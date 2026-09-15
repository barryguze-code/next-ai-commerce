package com.nextaicommerce.platform.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import com.nextaicommerce.platform.sync.AmazonSyncProgress;

@Repository
public class WorkspaceAccessRepository {
    public record AccountView(UUID id, String name, String initials, String slug, String status, int users, int connections, boolean hasLogo) {}
    public record AccountOption(UUID id, String name, String initials, String logoUrl, List<ConnectionView> stores) {}
    public record ConnectionView(UUID id, UUID tenantId, String tenantName, String name, String channel,
        String sellerId, String marketplaceId, String marketplaceLabel, String countryFlag,
        String status, Instant lastCheckedAt, boolean credentialsStored) {}
    public record ConnectionIdentity(UUID id, String channel, String marketplaceId) {
        public ConnectionIdentity(UUID id, String channel) { this(id, channel, "ATVPDKIKX0DER"); }
    }
    public record CredentialEnvelope(byte[] payload, byte[] nonce) {}
    public record SyncStatusView(UUID connectionId, String storeName, String status, int progress,
        String stage, String message, Instant startedAt) {
        public int displayProgress() {
            return AmazonSyncProgress.displayPercent(progress, stage, status);
        }
        public String stagePosition() {
            return "Step " + AmazonSyncProgress.stageNumber(stage) + " of " + AmazonSyncProgress.stageCount();
        }
        public String estimatedTimeRemaining() {
            if ("COMPLETED".equals(status)) return "Complete";
            return AmazonSyncProgress.stageTiming(stage);
        }
        public boolean starting() {
            return progress == 0 && !"COMPLETED".equals(status);
        }
    }
    public record SyncJobView(UUID connectionId, int sequence, String jobType, String label,
        String status, long recordsProcessed, String detail) {}
    public record MemberView(UUID id, String displayName, String email, String role, String status, int stores) {}
    public record InvitationView(UUID id, String email, String role, String status, int stores,
        Instant sentAt, Instant expiresAt, String invitedBy) {
        private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd/yy · h:mm a")
            .withZone(ZoneId.systemDefault());
        public boolean expired() { return !expiresAt.isAfter(Instant.now()); }
        public String displayStatus() { return expired() ? "EXPIRED" : status; }
        public String sentLabel() { return DATE_TIME.format(sentAt); }
        public String expiresLabel() { return DATE_TIME.format(expiresAt); }
    }

    private final JdbcTemplate jdbc;

    public WorkspaceAccessRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public List<AccountView> listAllAccounts() {
        List<AccountView> result = new ArrayList<>();
        for (var tenant : jdbc.query("SELECT id, display_name, slug, status, logo_bytes IS NOT NULL FROM tenants WHERE status = 'ACTIVE' ORDER BY display_name",
                (rs, row) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),rs.getBoolean(5)})) {
            UUID tenantId = (UUID) tenant[0];
            setTenant(tenantId);
            Integer users = jdbc.queryForObject("SELECT count(*) FROM tenant_memberships WHERE tenant_id = ?", Integer.class, tenantId);
            Integer connections = jdbc.queryForObject("SELECT count(*) FROM marketplace_connections WHERE tenant_id = ?", Integer.class, tenantId);
            String name = (String) tenant[1];
            result.add(new AccountView(tenantId, name, accountInitials(name), (String) tenant[2], (String) tenant[3],
                users == null ? 0 : users, connections == null ? 0 : connections,(Boolean)tenant[4]));
        }
        return result;
    }

    private static String accountInitials(String name) {
        if (name == null || name.isBlank()) return "BA";
        String[] words = name.trim().split("\\s+");
        if (words.length == 1) return words[0].substring(0, Math.min(3, words[0].length())).toUpperCase(Locale.ROOT);
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
        UUID tenantId=jdbc.queryForObject("INSERT INTO tenants (slug, display_name, status) VALUES (?, ?, 'ACTIVE') RETURNING id",
            UUID.class,slug,cleanName);
        setTenant(tenantId);
        jdbc.update("INSERT INTO warehouse_locations(tenant_id,code,name) VALUES (?,'MAIN','Main storage')",tenantId);
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
            .map(account -> new AccountOption(account.id(), account.name(), account.initials(),account.hasLogo()?"/app/platform/accounts/"+account.id()+"/logo":null,
                visibleConnections(account, email, superAdmin)))
            .toList();
    }

    @Transactional
    public void saveAccountLogo(UUID tenantId,String contentType,byte[] bytes){
        jdbc.update("UPDATE tenants SET logo_bytes=?,logo_content_type=?,logo_updated_at=now() WHERE id=?",bytes,contentType,tenantId);
    }
    @Transactional(readOnly=true)
    public LogoData accountLogo(UUID tenantId){
        return jdbc.query("SELECT logo_bytes,logo_content_type FROM tenants WHERE id=?",rs->rs.next()&&rs.getBytes(1)!=null?new LogoData(rs.getBytes(1),rs.getString(2)):null,tenantId);
    }
    public record LogoData(byte[] bytes,String contentType) {}

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
                FROM marketplace_connections WHERE tenant_id = ? AND status <> 'DISABLED' ORDER BY display_name
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
        return jdbc.query("SELECT id, channel, marketplace_identifier FROM marketplace_connections WHERE tenant_id=? AND id=?",
            (rs, row) -> new ConnectionIdentity(rs.getObject("id", UUID.class), rs.getString("channel"), rs.getString("marketplace_identifier")),
            tenantId, connectionId).stream().findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Marketplace connection was not found."));
    }

    @Transactional(readOnly = true)
    public CredentialEnvelope loadCredentials(UUID tenantId, UUID connectionId) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT encrypted_payload, encryption_nonce FROM marketplace_connection_credentials
            WHERE tenant_id=? AND marketplace_connection_id=?
            """, rs -> rs.next() ? new CredentialEnvelope(rs.getBytes(1), rs.getBytes(2)) : null,
            tenantId, connectionId);
    }

    @Transactional
    public void saveCredentials(UUID tenantId, UUID connectionId, byte[] payload, byte[] nonce,
            boolean initialize) {
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
            SET credential_secret_ref=?, status=?, last_synced_at=NULL
            WHERE tenant_id=? AND id=?
            """, "database-encrypted://marketplace-connection/" + connectionId,
            initialize ? "INITIALIZING" : "ACTIVE", tenantId, connectionId);
    }

    @Transactional(readOnly = true)
    public List<SyncStatusView> listCurrentSyncStatuses(UUID tenantId) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT r.marketplace_connection_id, c.display_name,
                   CASE WHEN final_job.id IS NOT NULL THEN 'COMPLETED'
                        WHEN NOT EXISTS (SELECT 1 FROM marketplace_sync_jobs required_job
                            WHERE required_job.tenant_id=r.tenant_id AND required_job.sync_run_id=r.id
                              AND required_job.required_for_ready=true
                              AND required_job.status NOT IN ('COMPLETED','SKIPPED')) THEN 'ENRICHING'
                        ELSE r.status END AS status,
                   CASE WHEN final_job.id IS NOT NULL THEN 100 ELSE r.progress_percent END AS progress_percent,
                   CASE WHEN final_job.id IS NOT NULL THEN 'FINAL_RECONCILIATION' ELSE r.current_stage END AS current_stage,
                   CASE WHEN final_job.id IS NOT NULL THEN 'Amazon data is ready' ELSE r.user_message END AS user_message,
                   r.started_at
            FROM marketplace_sync_runs r
            JOIN marketplace_connections c ON c.tenant_id=r.tenant_id AND c.id=r.marketplace_connection_id
            LEFT JOIN marketplace_sync_jobs final_job ON final_job.sync_run_id=r.id
                AND final_job.tenant_id=r.tenant_id
                AND final_job.job_type='FINAL_RECONCILIATION'
                AND final_job.status='COMPLETED'
            WHERE r.tenant_id=? AND r.id=(
                SELECT newest.id FROM marketplace_sync_runs newest
                WHERE newest.tenant_id=r.tenant_id
                  AND newest.marketplace_connection_id=r.marketplace_connection_id
                ORDER BY newest.created_at DESC LIMIT 1)
            ORDER BY c.display_name
            """, (rs, row) -> new SyncStatusView(
                rs.getObject("marketplace_connection_id", UUID.class), rs.getString("display_name"),
                rs.getString("status"), rs.getInt("progress_percent"), rs.getString("current_stage"),
                rs.getString("user_message"), rs.getTimestamp("started_at") == null ? null
                    : rs.getTimestamp("started_at").toInstant()), tenantId);
    }

    @Transactional(readOnly = true)
    public List<SyncJobView> listCurrentSyncJobs(UUID tenantId) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT j.marketplace_connection_id,j.sequence_number,j.job_type,j.status,
                   j.records_processed,j.error_message
            FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.id=j.sync_run_id
            WHERE j.tenant_id=? AND r.id=(SELECT newest.id FROM marketplace_sync_runs newest
                WHERE newest.tenant_id=r.tenant_id AND newest.marketplace_connection_id=r.marketplace_connection_id
                ORDER BY newest.created_at DESC LIMIT 1)
            ORDER BY j.marketplace_connection_id,j.sequence_number
            """, (rs,row)->new SyncJobView(rs.getObject(1,UUID.class),rs.getInt(2),rs.getString(3),
                syncLabel(rs.getString(3)),rs.getString(4),rs.getLong(5),rs.getString(6)),tenantId);
    }

    @Transactional(readOnly = true)
    public boolean historicalSyncInProgress(UUID tenantId) {
        setTenant(tenantId);
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM marketplace_sync_runs r
            WHERE r.tenant_id=? AND r.status IN ('QUEUED','RUNNING')
              AND r.sync_profile='INITIAL_30_DAY'
              AND NOT EXISTS (SELECT 1 FROM marketplace_sync_jobs required_job
                  WHERE required_job.tenant_id=r.tenant_id AND required_job.sync_run_id=r.id
                    AND required_job.required_for_ready=true
                    AND required_job.status NOT IN ('COMPLETED','SKIPPED'))
              AND EXISTS (SELECT 1 FROM marketplace_sync_jobs background_job
                  WHERE background_job.tenant_id=r.tenant_id AND background_job.sync_run_id=r.id
                    AND background_job.required_for_ready=false
                    AND background_job.status NOT IN ('COMPLETED','SKIPPED'))
            """,Integer.class,tenantId);
        return count!=null&&count>0;
    }

    @Transactional
    public void disconnectConnection(UUID tenantId, UUID connectionId) {
        setTenant(tenantId);
        Integer found=jdbc.queryForObject("SELECT count(*) FROM marketplace_connections WHERE tenant_id=? AND id=?",
            Integer.class,tenantId,connectionId);
        if(found==null||found!=1) throw new IllegalArgumentException("Marketplace store was not found.");
        jdbc.update("""
            UPDATE marketplace_sync_jobs SET status='SKIPPED',completed_at=now(),lease_owner=NULL,
                lease_expires_at=NULL,error_code='CONNECTION_DISCONNECTED',
                error_message='Store disconnected by an account administrator'
            WHERE tenant_id=? AND marketplace_connection_id=? AND status IN ('QUEUED','RUNNING','WAITING')
            """,tenantId,connectionId);
        jdbc.update("""
            UPDATE marketplace_sync_runs SET status='CANCELLED',completed_at=now(),
                user_message='Store disconnected'
            WHERE tenant_id=? AND marketplace_connection_id=? AND status IN ('QUEUED','RUNNING')
            """,tenantId,connectionId);
        jdbc.update("DELETE FROM marketplace_connection_credentials WHERE tenant_id=? AND marketplace_connection_id=?",
            tenantId,connectionId);
        jdbc.update("""
            UPDATE marketplace_connections SET status='DISABLED',credential_secret_ref=?,last_synced_at=NULL
            WHERE tenant_id=? AND id=?
            ""","disconnected://marketplace-connection/"+connectionId,tenantId,connectionId);
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
        int reactivated = "AMAZON".equals(channel)
            ? jdbc.update("""
                UPDATE marketplace_connections SET display_name=?,status='PENDING',
                    credential_secret_ref='pending://marketplace-connection',last_synced_at=NULL
                WHERE tenant_id=? AND channel=? AND seller_identifier=?
                  AND marketplace_identifier=? AND status='DISABLED'
                """,displayName.trim(),tenantId,channel,storedIdentifier,marketplaceIdentifier)
            : jdbc.update("""
                UPDATE marketplace_connections SET display_name=?,status='PENDING',
                    credential_secret_ref='pending://marketplace-connection',last_synced_at=NULL
                WHERE id=(SELECT id FROM marketplace_connections
                    WHERE tenant_id=? AND channel='WALMART' AND marketplace_identifier=?
                      AND lower(display_name)=lower(?) AND status='DISABLED'
                    ORDER BY updated_at DESC LIMIT 1)
                """,displayName.trim(),tenantId,marketplaceIdentifier,displayName.trim());
        if (reactivated == 1) return;
        int inserted = jdbc.update("""
            INSERT INTO marketplace_connections
                (tenant_id, channel, seller_identifier, marketplace_identifier,
                 credential_secret_ref, status, display_name, reporting_timezone)
            SELECT ?, ?, ?, ?, ?, 'PENDING', ?, definition.reporting_timezone
            FROM marketplace_definitions definition
            WHERE definition.channel=? AND definition.marketplace_identifier=?
            """, tenantId, channel, storedIdentifier, marketplaceIdentifier,
            "pending://marketplace-connection", displayName.trim(), channel, marketplaceIdentifier);
        if (inserted != 1) throw new IllegalArgumentException("Choose a supported marketplace.");
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

    @Transactional(readOnly = true)
    public List<InvitationView> listInvitations(UUID tenantId) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT invitation.id, invitation.email, invitation.role, invitation.status,
                   invitation.created_at, invitation.expires_at, inviter.display_name AS invited_by,
                   (SELECT count(*) FROM invitation_store_access access
                    WHERE access.tenant_id=invitation.tenant_id
                      AND access.invitation_id=invitation.id) AS stores
            FROM tenant_invitations invitation
            JOIN app_users inviter ON inviter.id=invitation.invited_by
            WHERE invitation.tenant_id=? AND invitation.status IN ('PENDING','EXPIRED')
            ORDER BY invitation.created_at DESC
            """, (rs, row) -> new InvitationView(
                rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("role"),
                rs.getString("status"), rs.getInt("stores"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("invited_by")), tenantId);
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

    private static String syncLabel(String type) {
        return switch(type) {
            case "VERIFY_SELLER" -> "Verify Amazon store";
            case "LISTINGS_SNAPSHOT" -> "Products and SKUs";
            case "ORDERS_30_DAY", "ORDER_ITEMS_30_DAY" -> type.startsWith("ORDER_ITEMS") ? "Order items" : "Last 30 days of orders";
            case "INVENTORY_SNAPSHOT" -> "Current FBA inventory";
            case "INVENTORY_LEDGER_30_DAY" -> "Inventory movement history";
            case "FBA_CUSTOMER_SHIPMENTS_30_DAY" -> "FBA customer shipments";
            case "RETURNS_30_DAY" -> "Customer returns";
            case "FINANCES_30_DAY" -> "Financial transactions";
            case "REIMBURSEMENTS_30_DAY" -> "Amazon reimbursements";
            case "FEES_SNAPSHOT" -> "FBA fee estimates";
            case "FINAL_RECONCILIATION" -> "Final data check";
            default -> "Amazon data";
        };
    }
}
