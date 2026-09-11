-- V16 initially encountered Amazon's fully quoted Ledger TSV format. Clear only
-- that normalization result so the immutable source is replayed by the corrected parser.
DO $$
DECLARE tenant_record RECORD;
BEGIN
    FOR tenant_record IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);

        DELETE FROM amazon_import_rejections
        WHERE tenant_id=tenant_record.id AND dataset='INVENTORY_LEDGER_30_DAY';

        UPDATE amazon_source_documents
        SET normalized_at=NULL,
            normalization_attempt_count=0,
            normalization_error=NULL,
            normalization_lease_owner=NULL,
            normalization_lease_expires_at=NULL
        WHERE tenant_id=tenant_record.id AND source_type='INVENTORY_LEDGER_30_DAY';
    END LOOP;
END $$;
