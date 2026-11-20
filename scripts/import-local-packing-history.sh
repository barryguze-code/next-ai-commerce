#!/usr/bin/env bash
set -euo pipefail

# Imports the user-supplied Packaging.txt history into the LOCAL UAT database.
# It never contacts Amazon and only works against the local forwarded port.
history_file=${1:?Usage: scripts/import-local-packing-history.sh /absolute/path/Packaging.txt}
database_url=${LOCAL_PACKING_DB_URL:-postgresql://127.0.0.1:55432/next_ai_commerce_local}
database_user=${LOCAL_PACKING_DB_USER:-nextcommerce_local}

if ! command -v psql >/dev/null 2>&1; then
  echo "PostgreSQL command-line tools are required to load the local packaging history (psql was not found)." >&2
  echo "Install the PostgreSQL client, then run this command again. The application and Amazon are not changed." >&2
  exit 1
fi

psql "$database_url" -U "$database_user" -v ON_ERROR_STOP=1 -v history_file="$history_file" <<'SQL'
CREATE TEMP TABLE packing_history_stage (
    amazon_order_id TEXT,
    order_item_summary TEXT,
    packaging TEXT,
    order_sku_qty_list TEXT,
    ignored_cost TEXT
);
\copy packing_history_stage FROM :'history_file' WITH (FORMAT csv, HEADER true, DELIMITER E'\t');

WITH selected_store AS (
    SELECT tenant_id,id FROM marketplace_connections
    WHERE channel='AMAZON' AND status='ACTIVE'
    ORDER BY created_at LIMIT 1
)
INSERT INTO temporary_order_packaging_lookup(
    tenant_id,marketplace_connection_id,amazon_order_id,order_item_summary,packaging,order_sku_qty_list
)
SELECT store.tenant_id,store.id,trim(stage.amazon_order_id),coalesce(stage.order_item_summary,''),trim(stage.packaging),
       coalesce(stage.order_sku_qty_list,'')
FROM packing_history_stage stage CROSS JOIN selected_store store
WHERE nullif(trim(stage.amazon_order_id),'') IS NOT NULL AND nullif(trim(stage.packaging),'') IS NOT NULL
ON CONFLICT (tenant_id,marketplace_connection_id,amazon_order_id) DO UPDATE SET
    order_item_summary=EXCLUDED.order_item_summary,packaging=EXCLUDED.packaging,
    order_sku_qty_list=EXCLUDED.order_sku_qty_list,imported_at=now();
SQL
