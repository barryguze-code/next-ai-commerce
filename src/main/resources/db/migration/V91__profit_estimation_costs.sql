-- Independent of landed product costs. One charge per marketplace SKU unit sold,
-- including a multipack; synchronization must not overwrite this account setting.
ALTER TABLE amazon_listings ADD COLUMN other_cost_per_sku NUMERIC(19,4) NOT NULL DEFAULT 1.00
    CHECK (other_cost_per_sku >= 0);

-- Append effective-dated overrides. The initial user-supplied USD defaults are
-- available from 2025-01-01 in ProfitShippingRates for every account, including new accounts.
CREATE TABLE profit_shipping_rates (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    package_type VARCHAR(30) NOT NULL CHECK (package_type IN (
        'FEDEX_XSMALL','FEDEX_PAK','FEDEX_SMALL','FEDEX_MEDIUM','FEDEX_LARGE','FEDEX_XLARGE','UPS')),
    effective_from DATE NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency='USD'),
    created_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,package_type,effective_from)
);
ALTER TABLE profit_shipping_rates ENABLE ROW LEVEL SECURITY;
ALTER TABLE profit_shipping_rates FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON profit_shipping_rates
    USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
