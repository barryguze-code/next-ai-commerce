-- Registered customer credentials are separate from PLATFORM project credentials.
-- Keep V104's original project-key constraint and existing forced tenant RLS intact.
ALTER TABLE carrier_connections
    ADD COLUMN registered_customer_payload BYTEA,
    ADD COLUMN registered_customer_nonce BYTEA,
    ADD CONSTRAINT carrier_registered_customer_pair CHECK (
        (registered_customer_payload IS NULL AND registered_customer_nonce IS NULL)
        OR (registered_customer_payload IS NOT NULL AND registered_customer_nonce IS NOT NULL
            AND octet_length(registered_customer_payload) >= 16
            AND octet_length(registered_customer_nonce) = 12
            AND credential_source = 'PLATFORM' AND environment = 'SANDBOX'
            AND deleted_at IS NULL)
    );
