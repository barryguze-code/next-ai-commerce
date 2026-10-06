-- Only initialize items that have never had vendor pricing. Never replace a
-- negotiated/default price, infer a packing-list cost, or rewrite ledger costs.
CREATE FUNCTION initialize_received_item_cost(account_id uuid, item_id uuid) RETURNS void
LANGUAGE plpgsql SET jit=off AS $$
DECLARE source record; offer_id uuid;
BEGIN
  IF account_id IS DISTINCT FROM nullif(current_setting('app.tenant_id',true),'')::uuid THEN
    RAISE EXCEPTION 'Account scope is required';
  END IF;
  PERFORM 1 FROM account_catalog_items WHERE tenant_id=account_id AND id=item_id FOR UPDATE;
  IF NOT FOUND OR EXISTS (SELECT 1 FROM vendor_catalog_offers
      WHERE tenant_id=account_id AND account_catalog_item_id=item_id) THEN RETURN; END IF;
  SELECT i.unit_cost,i.currency,i.vendor_item_code,p.vendor_id,d.document_number,d.id document_id,r.received_by
    INTO source
    FROM purchase_order_items i
    JOIN purchase_orders p ON p.tenant_id=i.tenant_id AND p.id=i.purchase_order_id
    JOIN receiving_documents d ON d.tenant_id=p.tenant_id AND d.id=p.receiving_document_id
    JOIN receiving_document_lines l ON l.tenant_id=i.tenant_id AND l.id=i.receiving_line_id
    JOIN receiving_sessions s ON s.tenant_id=d.tenant_id AND s.id=d.receiving_session_id
    JOIN receiving_line_receipts r ON r.tenant_id=i.tenant_id AND r.purchase_order_item_id=i.id
    WHERE i.tenant_id=account_id AND i.account_catalog_item_id=item_id
      AND d.document_type='INVOICE' AND d.removed_at IS NULL AND s.status<>'CANCELLED'
      AND r.voided_at IS NULL AND r.disposition IN ('SELLABLE','SOON_EXPIRED','EXPIRED')
      AND l.invoice_unit_cost>0 AND i.unit_cost>0 AND i.currency ~ '^[A-Z]{3}$'
    ORDER BY d.document_date DESC NULLS LAST,d.created_at DESC,r.received_at DESC,i.id LIMIT 1;
  IF NOT FOUND THEN RETURN; END IF;
  INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,
      list_cost,discount_rate,currency,is_default)
    VALUES(account_id,source.vendor_id,item_id,source.vendor_item_code,source.unit_cost,0,source.currency,true)
    RETURNING id INTO offer_id;
  INSERT INTO vendor_cost_history(tenant_id,vendor_offer_id,source_type,source_reference,list_cost,
      discount_rate,net_unit_cost,currency,changed_by)
    VALUES(account_id,offer_id,'INVOICE','Initialize missing default: '||source.document_number||' / '||source.document_id,
      source.unit_cost,0,source.unit_cost,source.currency,source.received_by);
  UPDATE account_catalog_items SET preferred_vendor_id=coalesce(preferred_vendor_id,source.vendor_id),updated_at=now()
    WHERE tenant_id=account_id AND id=item_id;
END $$;

CREATE FUNCTION initialize_receipt_cost_default() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.voided_at IS NULL AND NEW.disposition IN ('SELLABLE','SOON_EXPIRED','EXPIRED') THEN
    PERFORM initialize_received_item_cost(NEW.tenant_id,i.account_catalog_item_id)
      FROM purchase_order_items i WHERE i.tenant_id=NEW.tenant_id AND i.id=NEW.purchase_order_item_id;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER receipt_cost_default AFTER INSERT ON receiving_line_receipts
  FOR EACH ROW EXECUTE FUNCTION initialize_receipt_cost_default();

DO $$ DECLARE account_id uuid; item_id uuid; previous_scope text;
BEGIN
  previous_scope:=current_setting('app.tenant_id',true);
  FOR account_id IN SELECT id FROM tenants LOOP
    PERFORM set_config('app.tenant_id',account_id::text,true);
    FOR item_id IN SELECT DISTINCT i.account_catalog_item_id FROM purchase_order_items i
      WHERE i.tenant_id=account_id AND i.account_catalog_item_id IS NOT NULL AND i.received_quantity>0
        AND NOT EXISTS(SELECT 1 FROM vendor_catalog_offers v WHERE v.tenant_id=account_id AND v.account_catalog_item_id=i.account_catalog_item_id)
    LOOP PERFORM initialize_received_item_cost(account_id,item_id); END LOOP;
  END LOOP;
  PERFORM set_config('app.tenant_id',coalesce(previous_scope,''),true);
END $$;
