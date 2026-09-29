package com.nextaicommerce.platform.sync;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Requests an import through the existing background scheduler; never publishes prices or stock. */
@Service
public class AmazonManualListingSync {
    private final JdbcTemplate jdbc;
    public AmazonManualListingSync(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Availability(boolean allowed, String message) {}

    @Transactional(readOnly=true)
    public Availability availability(UUID tenant, UUID connection) {
        setTenant(tenant);
        return jdbc.query("""
            SELECT c.status,
              EXISTS(SELECT 1 FROM marketplace_sync_jobs j WHERE j.tenant_id=c.tenant_id
                AND j.marketplace_connection_id=c.id AND j.job_type='LISTINGS_SNAPSHOT'
                AND j.status IN ('QUEUED','RUNNING','WAITING')),
              (SELECT max(j.completed_at) FROM marketplace_sync_jobs j WHERE j.tenant_id=c.tenant_id
                AND j.marketplace_connection_id=c.id AND j.job_type='LISTINGS_SNAPSHOT' AND j.status='COMPLETED'),
              EXISTS(SELECT 1 FROM marketplace_sync_schedules s WHERE s.tenant_id=c.tenant_id
                AND s.marketplace_connection_id=c.id AND s.schedule_key='LISTINGS' AND s.enabled AND s.next_run_at<=now())
            FROM marketplace_connections c WHERE c.tenant_id=? AND c.id=? AND c.channel='AMAZON'
            """,rs->{
                if(!rs.next() || !"ACTIVE".equals(rs.getString(1))) return new Availability(false,"Verify the Amazon connection before syncing.");
                if(rs.getBoolean(2) || rs.getBoolean(4)) return new Availability(false,"Amazon SKU sync is queued or running. Refresh to see updated listings.");
                var completed=rs.getTimestamp(3);
                if(completed!=null && completed.toInstant().plusSeconds(300).isAfter(Instant.now()))
                    return new Availability(false,"Amazon SKUs were recently synced. Try again in five minutes.");
                return new Availability(true,"Refresh listings from Amazon.");
            },tenant,connection);
    }

    @Transactional
    public void request(UUID tenant, UUID connection) {
        setTenant(tenant);
        // Serializes manual requests. The scheduler takes the same schedule row lock.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,53))",rs->{},connection);
        jdbc.query("SELECT schedule_key FROM marketplace_sync_schedules WHERE tenant_id=? AND marketplace_connection_id=? AND schedule_key='LISTINGS' FOR UPDATE",rs->{},tenant,connection);
        var current=availability(tenant,connection);
        if(!current.allowed()) throw new IllegalStateException(current.message());
        jdbc.update("""
            INSERT INTO marketplace_sync_schedules(tenant_id,marketplace_connection_id,schedule_key,
                run_type,cadence_seconds,lookback_seconds,priority,next_run_at)
            VALUES(?,?,'LISTINGS','RECONCILIATION',86400,0,70,now())
            ON CONFLICT(tenant_id,marketplace_connection_id,schedule_key) DO UPDATE
                SET enabled=true,next_run_at=now(),updated_at=now()
            """,tenant,connection);
    }

    private void setTenant(UUID tenant) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
    }
}
