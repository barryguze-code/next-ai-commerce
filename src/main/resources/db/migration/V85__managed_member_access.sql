-- Preserve existing administrator access until explicitly managed in Users & Access.
ALTER TABLE tenant_memberships ADD COLUMN stores_restricted BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE record_picture_overrides DROP CONSTRAINT record_picture_overrides_entity_type_check;
ALTER TABLE record_picture_overrides ADD CONSTRAINT record_picture_overrides_entity_type_check
 CHECK (entity_type IN ('VENDOR','SHIPMENT','USER'));
