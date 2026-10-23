CREATE TABLE shipping_print_preferences (
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 marketplace_connection_id UUID NOT NULL REFERENCES marketplace_connections(id),
 label_size VARCHAR(16) NOT NULL DEFAULT 'THERMAL_4X6' CHECK(label_size IN ('THERMAL_4X6','LETTER')),
 layout VARCHAR(32) NOT NULL DEFAULT 'ORIGINAL' CHECK(layout IN ('ORIGINAL','SEPARATE_SLIP','COMBINED_80_20')),
 PRIMARY KEY(tenant_id,marketplace_connection_id),
 CHECK(label_size='THERMAL_4X6' OR layout<>'COMBINED_80_20')
);
CREATE TABLE shipping_order_packages (
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 marketplace_connection_id UUID NOT NULL REFERENCES marketplace_connections(id),
 amazon_order_id VARCHAR(100) NOT NULL,
 package_profile_id UUID NOT NULL REFERENCES shipping_package_profiles(id),
 composition TEXT NOT NULL,
 PRIMARY KEY(tenant_id,marketplace_connection_id,amazon_order_id)
);
ALTER TABLE shipping_print_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipping_print_preferences FORCE ROW LEVEL SECURITY;
CREATE POLICY shipping_print_preferences_tenant ON shipping_print_preferences USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
ALTER TABLE shipping_order_packages ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipping_order_packages FORCE ROW LEVEL SECURITY;
CREATE POLICY shipping_order_packages_tenant ON shipping_order_packages USING(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
