-- Forward-only correction: V130/V131 already applied in Local UAT remain unchanged.
ALTER POLICY warehouses_isolation ON warehouses
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
ALTER POLICY warehouse_contacts_isolation ON warehouse_contacts
 USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE OR REPLACE FUNCTION initialize_account_warehouse() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,"${flyway:defaultSchema}",pg_temp AS $$
DECLARE previous_scope text;
BEGIN
 previous_scope:=current_setting('app.tenant_id',true);
 PERFORM set_config('app.tenant_id',NEW.id::text,true);
 INSERT INTO "${flyway:defaultSchema}".warehouses(tenant_id,name,is_default) VALUES(NEW.id,'Default Warehouse',true);
 PERFORM set_config('app.tenant_id',coalesce(previous_scope,''),true);
 RETURN NEW;
END $$;
