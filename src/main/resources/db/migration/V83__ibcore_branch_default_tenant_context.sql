-- vendors is tenant protected; global supplier-code rows are not. Flyway runs
-- under the application role in production, so establish the explicit account
-- context for this user-authorized, tenant-scoped default backfill.
SELECT set_config('app.tenant_id','d018e963-2c60-4b41-9331-a6232e1981b2',true);
UPDATE vendors SET distribution_center='41'
 WHERE tenant_id='d018e963-2c60-4b41-9331-a6232e1981b2'
   AND upper(regexp_replace(coalesce(vendor_code,name),'[^A-Za-z0-9]','','g'))='KEHE'
   AND distribution_center='';
