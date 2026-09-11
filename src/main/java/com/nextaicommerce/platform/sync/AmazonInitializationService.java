package com.nextaicommerce.platform.sync;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AmazonInitializationService {
    private static final List<Step> STEPS = List.of(
        new Step("VERIFY_SELLER", true),
        new Step("LISTINGS_SNAPSHOT", true),
        new Step("ORDERS_30_DAY", true),
        new Step("ORDER_ITEMS_30_DAY", true),
        new Step("INVENTORY_SNAPSHOT", true),
        new Step("INVENTORY_LEDGER_30_DAY", false),
        new Step("RETURNS_30_DAY", false),
        new Step("FINANCES_30_DAY", false),
        new Step("REIMBURSEMENTS_30_DAY", false),
        new Step("FEES_SNAPSHOT", false),
        new Step("FBA_CUSTOMER_SHIPMENTS_30_DAY", false),
        new Step("FINAL_RECONCILIATION", false));

    private final JdbcTemplate jdbc;

    public AmazonInitializationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public UUID enqueue(UUID tenantId, UUID connectionId) {
        setTenant(tenantId);
        List<UUID> existing = jdbc.query("""
            SELECT id FROM marketplace_sync_runs
            WHERE tenant_id=? AND marketplace_connection_id=? AND run_type='INITIAL_30_DAY'
              AND status IN ('QUEUED','RUNNING')
            ORDER BY created_at DESC LIMIT 1
            """, (rs, row) -> rs.getObject(1, UUID.class), tenantId, connectionId);
        if (!existing.isEmpty()) return existing.getFirst();

        Instant end = Instant.now();
        Instant start = end.minus(30, ChronoUnit.DAYS);
        UUID runId = jdbc.queryForObject("""
            INSERT INTO marketplace_sync_runs
                (tenant_id, marketplace_connection_id, run_type, window_start, window_end,
                 current_stage, user_message)
            VALUES (?, ?, 'INITIAL_30_DAY', ?, ?, 'VERIFY_SELLER', 'Verifying your Amazon store')
            RETURNING id
            """, UUID.class, tenantId, connectionId, Timestamp.from(start), Timestamp.from(end));
        for (int i = 0; i < STEPS.size(); i++) {
            Step step = STEPS.get(i);
            jdbc.update("""
                INSERT INTO marketplace_sync_jobs
                    (tenant_id, sync_run_id, marketplace_connection_id, job_type,
                     sequence_number, required_for_ready)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenantId, runId, connectionId, step.name(), i + 1, step.required());
        }
        return runId;
    }

    private void setTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private record Step(String name, boolean required) {}
}
