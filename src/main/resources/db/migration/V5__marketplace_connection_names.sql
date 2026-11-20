ALTER TABLE marketplace_connections
    ADD COLUMN display_name VARCHAR(160) NOT NULL DEFAULT 'Amazon Connection';

ALTER TABLE marketplace_connections ALTER COLUMN display_name DROP DEFAULT;
