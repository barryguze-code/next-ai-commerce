ALTER TABLE tenants
    ADD COLUMN legal_business_name varchar(240),
    ADD COLUMN registered_business_address varchar(1200);
