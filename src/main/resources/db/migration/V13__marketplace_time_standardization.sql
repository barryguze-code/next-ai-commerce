CREATE TABLE marketplace_definitions (
    channel VARCHAR(30) NOT NULL,
    marketplace_identifier VARCHAR(120) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    country_code CHAR(2) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    reporting_timezone VARCHAR(80) NOT NULL,
    PRIMARY KEY (channel, marketplace_identifier)
);

INSERT INTO marketplace_definitions(channel,marketplace_identifier,display_name,country_code,currency_code,reporting_timezone) VALUES
    ('AMAZON','ATVPDKIKX0DER','Amazon US','US','USD','America/Los_Angeles'),
    ('AMAZON','A1F83G8C2ARO7P','Amazon UK','GB','GBP','Europe/London'),
    ('AMAZON','A2EUQ1WTGCTBG2','Amazon Canada','CA','CAD','America/Toronto'),
    ('WALMART','Walmart-US','Walmart US','US','USD','America/Los_Angeles');

ALTER TABLE marketplace_connections ADD COLUMN reporting_timezone VARCHAR(80);

-- marketplace_connections is protected by forced tenant row-level security.
-- Backfill each tenant inside its own database context so existing rows are visible.
DO $$
DECLARE
    tenant_record RECORD;
BEGIN
    FOR tenant_record IN SELECT id FROM tenants
    LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
        UPDATE marketplace_connections connection
        SET reporting_timezone=definition.reporting_timezone
        FROM marketplace_definitions definition
        WHERE connection.tenant_id=tenant_record.id
          AND definition.channel=connection.channel
          AND definition.marketplace_identifier=connection.marketplace_identifier;
    END LOOP;
END $$;

ALTER TABLE marketplace_connections ALTER COLUMN reporting_timezone SET NOT NULL;

ALTER TABLE amazon_orders
    ADD COLUMN purchase_marketplace_date DATE,
    ADD COLUMN last_update_marketplace_date DATE;
ALTER TABLE amazon_inventory_events ADD COLUMN event_marketplace_date DATE;
ALTER TABLE amazon_returns ADD COLUMN return_marketplace_date DATE;
ALTER TABLE amazon_financial_transactions ADD COLUMN posted_marketplace_date DATE;
ALTER TABLE amazon_reimbursements ADD COLUMN approval_marketplace_date DATE;

CREATE INDEX amazon_orders_marketplace_date_idx
    ON amazon_orders(tenant_id,marketplace_connection_id,purchase_marketplace_date DESC);
CREATE INDEX amazon_inventory_events_marketplace_date_idx
    ON amazon_inventory_events(tenant_id,marketplace_connection_id,event_marketplace_date DESC);
