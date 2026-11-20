-- Keep immutable product/account/stock IDs. Barcodes are business identity;
-- distributor item codes identify a product only inside one branch.
CREATE FUNCTION catalog_identifier_key(kind text, value text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
 SELECT CASE WHEN upper(kind) IN ('UPC','EAN','GTIN')
   AND value ~ '^([0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14})$'
   THEN 'GTIN:' || lpad(value,14,'0') ELSE upper(kind)||':'||value END
$$;
ALTER TABLE global_product_identifiers ADD COLUMN identity_key text
 GENERATED ALWAYS AS (catalog_identifier_key(identifier_type,identifier_value)) STORED;
CREATE UNIQUE INDEX global_product_barcode_identity_idx ON global_product_identifiers(identity_key);

ALTER TABLE vendors ADD COLUMN distribution_center varchar(40) NOT NULL DEFAULT '';
ALTER TABLE global_product_vendor_codes ADD COLUMN catalog_scope text NOT NULL DEFAULT 'LEGACY';
ALTER TABLE global_product_packaging_versions ADD COLUMN catalog_scope text NOT NULL DEFAULT 'LEGACY';
CREATE FUNCTION catalog_vendor_scope(account_id uuid, dc text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
 SELECT CASE WHEN coalesce(dc,'')='' THEN 'ACCOUNT:'||account_id::text ELSE 'DC:'||dc END
$$;

-- Explicitly authorized legacy scope: Ibcore's KEHE catalogue is DC 41.
UPDATE vendors SET distribution_center='41'
 WHERE tenant_id='d018e963-2c60-4b41-9331-a6232e1981b2'
 AND upper(regexp_replace(coalesce(vendor_code,name),'[^A-Za-z0-9]','','g'))='KEHE';
UPDATE global_product_vendor_codes code SET catalog_scope=CASE
 WHEN code.vendor_key='KEHE' AND (code.source_tenant_id='d018e963-2c60-4b41-9331-a6232e1981b2'
   OR EXISTS (SELECT 1 FROM vendor_catalog_offers offer
     JOIN vendors vendor ON vendor.id=offer.vendor_id AND vendor.tenant_id=offer.tenant_id
     JOIN account_catalog_items item ON item.id=offer.account_catalog_item_id AND item.tenant_id=offer.tenant_id
     WHERE offer.tenant_id='d018e963-2c60-4b41-9331-a6232e1981b2' AND vendor.distribution_center='41'
       AND item.global_product_id=code.global_product_id
       AND regexp_replace(upper(regexp_replace(offer.vendor_item_code,'[^A-Za-z0-9]','','g')),'^0+(?!$)','')=code.normalized_item_code))
 THEN 'DC:41'
 ELSE coalesce(catalog_vendor_scope(code.source_tenant_id,''),'LEGACY') END;
UPDATE global_product_packaging_versions version SET catalog_scope=code.catalog_scope
 FROM global_product_vendor_codes code WHERE code.vendor_key=version.vendor_key
 AND code.normalized_item_code=version.normalized_vendor_item_code AND code.global_product_id=version.global_product_id;
DROP INDEX global_product_vendor_code_identity_idx;
CREATE UNIQUE INDEX global_product_vendor_code_identity_idx
 ON global_product_vendor_codes(vendor_key,catalog_scope,normalized_item_code);
CREATE INDEX global_product_vendor_code_branch_lookup_idx
 ON global_product_vendor_codes(vendor_key,normalized_item_code) INCLUDE(catalog_scope,global_product_id);
DROP INDEX global_packaging_one_active_vendor_item_idx;
CREATE UNIQUE INDEX global_packaging_one_active_vendor_item_idx
 ON global_product_packaging_versions(vendor_key,catalog_scope,normalized_vendor_item_code) WHERE status='ACTIVE';

-- A separate vendor entry represents each branch an account purchases from.
-- Existing IDs, prices, receipts and inventory are not moved or rewritten.
ALTER TABLE vendors DROP CONSTRAINT vendors_tenant_id_vendor_code_key;
ALTER TABLE vendors ADD CONSTRAINT vendors_account_supplier_branch_key
 UNIQUE(tenant_id,vendor_code,distribution_center);
