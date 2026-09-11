package com.nextaicommerce.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FlywayMigrationSafetyTest {
    @Test
    void liveOrderBoundaryBackfillsLegacyRowsOutsideTenantRls() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration/V31__live_order_fulfillment.sql")){
            assertThat(input).isNotNull();
            String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            int disable=sql.indexOf("DISABLE ROW LEVEL SECURITY");
            int backfill=sql.indexOf("UPDATE marketplace_connections SET inventory_activated_at=now()");
            int enable=sql.indexOf("ENABLE ROW LEVEL SECURITY");
            int force=sql.indexOf("FORCE ROW LEVEL SECURITY",enable);
            int mandatory=sql.indexOf("ALTER COLUMN inventory_activated_at SET NOT NULL");
            assertThat(disable).isGreaterThanOrEqualTo(0);
            assertThat(backfill).isGreaterThan(disable);
            assertThat(enable).isGreaterThan(backfill);
            assertThat(force).isGreaterThan(enable);
            assertThat(mandatory).isGreaterThan(force);
        }
    }

    @Test
    void buyShippingTablesAreTenantIsolatedAndPurchasesDefaultToPreviewOnly() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration/V53__amazon_buy_shipping.sql")){
            assertThat(input).isNotNull();
            String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            assertThat(sql).contains("DEFAULT 'RATES_ONLY'","encrypted_payload BYTEA","PURCHASE_UNKNOWN",
                "ENABLE ROW LEVEL SECURITY","FORCE ROW LEVEL SECURITY","buy_shipping_cost_allocations");
        }
    }

    @Test
    void shippingDeskBatchesAreDurableTenantScopedAndReprintable() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration/V54__shipping_desk_batches.sql")){
            assertThat(input).isNotNull();
            String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            assertThat(sql).contains("buy_shipping_batches","buy_shipping_batch_orders","policy_snapshot JSONB NOT NULL",
                "access_count INTEGER NOT NULL","shipping_label_library_idx","buy_shipping_one_active_batch_order_idx",
                "ENABLE ROW LEVEL SECURITY","FORCE ROW LEVEL SECURITY");
        }
    }

    @Test
    void collaborationMentionsAreTenantScopedAndDurablyRetried() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration/V56__collaboration_mentions.sql")){
            assertThat(input).isNotNull();
            String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            assertThat(sql).contains("collaboration_mentions","next_attempt_at","attempt_count",
                "ENABLE ROW LEVEL SECURITY","FORCE ROW LEVEL SECURITY","collaboration_mentions_isolation");
        }
    }

    @Test
    void collaborationCompletionCanNotifyEveryParticipantWithoutDuplicatingMentions() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration/V57__collaboration_completion_notifications.sql")){
            assertThat(input).isNotNull();
            String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            assertThat(sql).contains("notification_kind","'MENTION','COMPLETED'",
                "collaboration_mentions_message_user_kind_key","message_id, mentioned_user_id, notification_kind");
        }
    }

    @Test
    void contextualThreadsKeepPrivateNotesPrivateAtTheDatabaseBoundary() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration/V58__contextual_threads_private_notes_and_attachments.sql")){
            assertThat(input).isNotNull();
            String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            assertThat(sql).contains("context_snapshot JSONB","message_type VARCHAR(20)","'TEAM_CHAT','PRIVATE_NOTE'",
                "collaboration_attachments","content BYTEA","app.user_email","collaboration_messages_visibility",
                "lower(author_email)=lower","status IN ('ACTIVE','CLOSED')","ENABLE ROW LEVEL SECURITY","FORCE ROW LEVEL SECURITY");
            assertThat(sql.indexOf("ALTER TABLE collaboration_reviews DISABLE ROW LEVEL SECURITY"))
                .isLessThan(sql.indexOf("UPDATE collaboration_reviews review"));
            assertThat(sql.indexOf("ALTER TABLE collaboration_messages DISABLE ROW LEVEL SECURITY"))
                .isLessThan(sql.indexOf("UPDATE collaboration_messages message"));
        }
    }

    @Test
    void inventoryCollaborationKeysAreMigratedToStableIsoDatesOutsideTenantRls() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration/V59__canonical_inventory_collaboration_keys.sql")){
            assertThat(input).isNotNull();
            String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            int disable=sql.indexOf("DISABLE ROW LEVEL SECURITY");
            int update=sql.indexOf("UPDATE collaboration_reviews");
            int iso=sql.indexOf("'YYYY-MM-DD'");
            int enable=sql.indexOf("ENABLE ROW LEVEL SECURITY");
            int force=sql.indexOf("FORCE ROW LEVEL SECURITY");
            assertThat(disable).isGreaterThanOrEqualTo(0);
            assertThat(update).isGreaterThan(disable);
            assertThat(iso).isGreaterThan(update);
            assertThat(enable).isGreaterThan(iso);
            assertThat(force).isGreaterThan(enable);
        }
    }

    @Test
    void shelfLifeAutomationKeepsSkuPlansTenantScopedAndLocalFirst() throws Exception {
        try(var input=getClass().getResourceAsStream("/db/migration/V60__inventory_shelf_life_operating_policy.sql")){
            assertThat(input).isNotNull();
            String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            assertThat(sql).contains("auto_remove_from_available BOOLEAN NOT NULL DEFAULT TRUE",
                "default_sale_discount_percent","inventory_shelf_life_product_overrides",
                "inventory_expiration_action_targets","ENABLE ROW LEVEL SECURITY",
                "FORCE ROW LEVEL SECURITY","inventory_expiration_action_targets_isolation");
        }
        try(var input=getClass().getResourceAsStream("/db/migration/V61__clarify_marketplace_sellable_policy.sql")){
            assertThat(input).isNotNull();
            String sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            assertThat(sql).contains("RENAME COLUMN auto_remove_from_available TO auto_zero_marketplace_sellable",
                "Physical inventory remains visible until a ledger movement removes it");
        }
    }

    @Test
    void amazonListingMutationsRemainDisabledUntilExplicitlyEnabled() throws Exception {
        try(var input=getClass().getResourceAsStream("/application.yml")){
            assertThat(input).isNotNull();
            String yaml=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            assertThat(yaml).contains("listing-actions-enabled: ${AMAZON_LISTING_ACTIONS_ENABLED:false}");
        }
        var condition=com.nextaicommerce.platform.sync.AmazonListingActionWorker.class
            .getAnnotation(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("app.amazon");
        assertThat(condition.name()).containsExactly("listing-actions-enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
    }
}
