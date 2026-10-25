#!/usr/bin/env bash
# Invoked by the protected production workflow on the existing SSM target only.
set -euo pipefail
revision=${1:?Revision required}
version=${2:?Version required}
package_sha=${3:?Package checksum required}
[[ "$revision" =~ ^[0-9a-f]{40}$ && "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
[[ "$package_sha" =~ ^[0-9a-f]{64}$ ]]
exec 9>/opt/next-ai-commerce/.release.lock
flock -n 9
test -s /opt/next-ai-commerce/next-ai-commerce.jar
runtime_config=/opt/next-ai-commerce/bin/start
test -s "$runtime_config"
grep -Eq 'SPRING_PROFILES_ACTIVE=prod([[:space:]]|$)' "$runtime_config"
systemctl is-active --quiet next-ai-commerce

# Release preparation: fail closed before downloads, backups or service changes.
# Required values must be reviewed and installed separately; never enable work here.
require_release_false() {
  local setting=$1
  local declarations expected
  declarations=$(grep -Ec "^[[:space:]]*(export[[:space:]]+)?${setting}=" "$runtime_config" || true)
  expected=$(grep -Ec "^[[:space:]]*(export[[:space:]]+)?${setting}=false[[:space:]]*$" "$runtime_config" || true)
  if [[ "$declarations" != 1 || "$expected" != 1 ]]; then
    echo "Release requires one explicit false setting: $setting. Runtime unchanged." >&2
    return 1
  fi
}
# BEGIN CATALOGUE MINOR BASELINE
verify_catalogue_minor_baseline() {
  # Exact reviewed 1.8.0 -> 1.9.0 application-only transition. This is not a backup waiver.
  test "${1:-}" = OWNER_APPROVED_CATALOGUE_MINOR_1_9
  test "$(sha256sum /opt/next-ai-commerce/next-ai-commerce.jar | cut -d' ' -f1)" = fec818b5a4ff56b33d264f41c0c6f42a9dd6fac318057af507b23d954270a4dd
  test "$(sha256sum "$runtime_config" | cut -d' ' -f1)" = e606097b8e990ee6675de0a0c78b8fd59328c5aa9e88ae12053d6fdbe49cbe95
  test "$(sudo -u postgres psql -d next_ai_commerce -XAtqc "SELECT max(version::int) FROM flyway_schema_history WHERE success AND version IS NOT NULL")" = 114
  test "$(sudo -u postgres psql -d next_ai_commerce -XAtqc "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL")" = 114
  test "$(sudo -u postgres psql -d next_ai_commerce -XAtqc "SELECT count(*) FROM flyway_schema_history WHERE NOT success")" = 0
  for setting in SPRING_FLYWAY_ENABLED AMAZON_PRODUCT_FEES_ENABLED AMAZON_PRODUCT_FEES_BATCH_ENABLED AMAZON_PRODUCT_FEES_RECURRING_ENABLED AMAZON_FEE_EVIDENCE_ENABLED AMAZON_FEE_APPROVALS_ENABLED AMAZON_FEE_PILOT_ENABLED AMAZON_FEE_PILOT_ONLY_SCHEDULING; do
    require_release_false "$setting"
  done
}
# END CATALOGUE MINOR BASELINE
# Owner-approved eligible UI-only 1.8.1 hotfix. Default releases still take a dump.
ui_hotfix=false
if [[ "$version" == "1.8.1" && "${4:-}" == "OWNER_APPROVED_UI_HOTFIX_NO_NEW_BACKUP" ]]; then
  test "$(sha256sum /opt/next-ai-commerce/next-ai-commerce.jar | cut -d' ' -f1)" = fec818b5a4ff56b33d264f41c0c6f42a9dd6fac318057af507b23d954270a4dd
  test "$(sudo -u postgres psql -d next_ai_commerce -XAtqc "SELECT max(version::int) FROM flyway_schema_history WHERE success AND version IS NOT NULL")" = 114
  test "$(sudo -u postgres psql -d next_ai_commerce -XAtqc "SELECT count(*) FROM flyway_schema_history WHERE NOT success")" = 0
  test -s /opt/next-ai-commerce/backups/v1.8.0-31340369-recovery-20261025a/recovery-receipt.json
  for setting in SPRING_FLYWAY_ENABLED AMAZON_PRODUCT_FEES_ENABLED AMAZON_PRODUCT_FEES_BATCH_ENABLED AMAZON_PRODUCT_FEES_RECURRING_ENABLED AMAZON_FEE_EVIDENCE_ENABLED AMAZON_FEE_APPROVALS_ENABLED AMAZON_FEE_PILOT_ENABLED AMAZON_FEE_PILOT_ONLY_SCHEDULING; do
    require_release_false "$setting"
  done
  ui_hotfix=true
fi
# BEGIN REVIEWED RELEASE GUARDS
if [[ "$version" == "1.8.0" ]]; then
  # Exact reviewed helper is delivered beside this script by the protected workflow.
  # No DDL or activation is performed here; missing evidence fails before downloads.
  manifest_result=$(FEE_RELEASE_OPERATION_SCRIPT="${BASH_SOURCE[0]}" python3 "${FEE_RELEASE_GUARD:?Reviewed 1.8 manifest guard required}" "$revision" "$version" "$package_sha" "${4:?Reviewed manifest checksum required}")
  printf '%s\n' "$manifest_result"
  verified_preddl_dump=$(printf '%s\n' "$manifest_result" | sed -n 's/^FEE_VERIFIED_DUMP=//p')
  verified_dump_sha=$(printf '%s\n' "$manifest_result" | sed -n 's/^FEE_VERIFIED_DUMP_SHA256=//p')
  verified_dump_bytes=$(printf '%s\n' "$manifest_result" | sed -n 's/^FEE_VERIFIED_DUMP_BYTES=//p')
  verified_package_bytes=$(printf '%s\n' "$manifest_result" | sed -n 's/^FEE_PACKAGE_BYTES=//p')
  test -f "$verified_preddl_dump" && test ! -L "$verified_preddl_dump"
  [[ "$verified_dump_sha" =~ ^[0-9a-f]{64}$ && "$verified_dump_bytes" =~ ^[1-9][0-9]*$ && "$verified_package_bytes" =~ ^[1-9][0-9]*$ ]]
elif [[ "$version" == "1.9.0" ]]; then
  verify_catalogue_minor_baseline "${4:-}"
elif [[ "$ui_hotfix" == true ]]; then
  : # Preserve the previously reviewed live worker policy and read-only fee UI.
else
for setting in APP_FEDEX_CONFIRMATION_ENABLED APP_SCHEDULING_ENABLED SPRING_FLYWAY_ENABLED MAIL_ENABLED   AMAZON_PRODUCT_FEES_ENABLED AMAZON_SYNC_ENABLED AMAZON_RECURRING_SYNC_ENABLED   AMAZON_STARTUP_ORDER_RECONCILIATION_ENABLED AMAZON_WRITE_ENABLED   AMAZON_LISTING_ACTIONS_ENABLED AMAZON_INVENTORY_PUBLICATION_ENABLED AMAZON_SHELF_SALE_ENABLED AMAZON_BUY_SHIPPING_ENABLED AMAZON_UNRESOLVED_ORDER_CHECKS_ENABLED APP_AMAZON_RECIPIENT_IMPORT_ENABLED APP_AMAZON_CATALOG_BARCODES_ENABLED APP_AMAZON_CATALOG_IMAGES_ENABLED   APP_AMAZON_VARIATION_OPERATOR_ENABLED APP_AMAZON_VARIATION_SYNC_ENABLED   APP_AMAZON_VARIATION_COVERAGE_ENABLED APP_AMAZON_VARIATION_INVENTORY_ENFORCEMENT_ENABLED   APP_AMAZON_VARIATION_TRIAL_ONLY_SCHEDULING; do
  require_release_false "$setting"
done
fi
# END REVIEWED RELEASE GUARDS
# Migrations require a separate approved operation before this Flyway-disabled launch.
# Verify the complete newly introduced dependency range before changing the service.
required_schema_count=$(sudo -u postgres psql -d next_ai_commerce -XAtqc "SELECT count(*) FROM public.flyway_schema_history WHERE version IN ('104','105','106','107','108','109','110','111','112') AND success")
test "$required_schema_count" = 9

# Leave room for the package, rollback copy, database backup and live writes.
# Refuse early instead of exhausting the filesystem beside a running database.
database_bytes=$(sudo -u postgres psql -XAtqc "SELECT pg_database_size('next_ai_commerce')")
[[ "$database_bytes" =~ ^[0-9]+$ ]]
current_jar_bytes=$(stat -Lc %s /opt/next-ai-commerce/next-ai-commerce.jar)
required_bytes=$((database_bytes * 2 + current_jar_bytes * 3 + 1073741824))
if [[ "$ui_hotfix" == true ]]; then
  required_bytes=$((current_jar_bytes * 3 + 1073741824))
elif [[ "$version" == "1.8.0" ]]; then
  # The verified pre-DDL dump and restored database are already on disk. No second
  # full dump is created. Reserve three largest JARs plus 1 GiB for live growth.
  largest_jar=$((current_jar_bytes > verified_package_bytes ? current_jar_bytes : verified_package_bytes))
  required_bytes=$((largest_jar * 3 + 1073741824))
fi
for directory in /opt/next-ai-commerce/releases /opt/next-ai-commerce/backups; do
  available_bytes=$(df -B1 --output=avail "$directory" | tail -n 1 | tr -d ' ')
  if (( available_bytes < required_bytes )); then
    echo "Insufficient deployment disk space: need $required_bytes bytes free; have $available_bytes. Live application unchanged." >&2
    exit 1
  fi
done

# Install the exact CI-tested artifact without compiling beside the live application.
umask 077
package_dir=$(mktemp -d /opt/next-ai-commerce/releases/.download-XXXXXX)
package_file="$package_dir/application.jar"
cleanup_package() {
  rm -f -- "$package_file"
  rmdir -- "$package_dir" 2>/dev/null || true
}
trap cleanup_package EXIT
curl --fail --location --silent --show-error --proto '=https' --proto-redir '=https' \
  --connect-timeout 15 --max-time 300 \
  "https://github.com/barryguze-code/next-ai-commerce/releases/download/build-${revision}/next-ai-commerce-${version}-${revision}.jar" \
  --output "$package_file"
printf '%s  %s\n' "$package_sha" "$package_file" | sha256sum --check --status
if [[ "$version" == "1.8.0" ]]; then test "$(stat -Lc %s "$package_file")" = "$verified_package_bytes"; fi
release_jar="/opt/next-ai-commerce/releases/next-ai-commerce-${version}-${revision:0:12}.jar"
install -o nextaicommerce -g nextaicommerce -m 0640 "$package_file" "$release_jar"
cleanup_package

# Keep the full database and rollback artifact on the server, never in CI output.
backup_dir=$(mktemp -d "/opt/next-ai-commerce/backups/v${version}-${revision:0:12}-XXXXXX")
install -d -m 0700 "$backup_dir"
cp -p "$runtime_config" "$backup_dir/start"
cp -L /opt/next-ai-commerce/next-ai-commerce.jar "$backup_dir/previous.jar"
chmod 0600 "$backup_dir/previous.jar"
umask 077
# BEGIN VERIFIED BACKUP REUSE
reuse_verified_dump() {
  # ln fails on another filesystem or an existing destination; no copy/overwrite fallback.
  ln -- "$verified_preddl_dump" "$backup_dir/database.dump"
  python3 - "$verified_preddl_dump" "$backup_dir/database.dump" "$verified_dump_sha" "$verified_dump_bytes" <<'PY'
import hashlib, pathlib, stat, sys
a,b=map(pathlib.Path,sys.argv[1:3]);x,y=a.stat(),b.stat()
assert not a.is_symlink() and not b.is_symlink()
assert (x.st_dev,x.st_ino,x.st_uid,x.st_size)==(y.st_dev,y.st_ino,y.st_uid,y.st_size)
assert stat.S_IMODE(x.st_mode)==stat.S_IMODE(y.st_mode)==0o600 and y.st_size==int(sys.argv[4])
h=hashlib.sha256()
with b.open('rb') as f:
    for block in iter(lambda:f.read(1048576),b''):h.update(block)
assert h.hexdigest()==sys.argv[3]
PY
}
if [[ "$ui_hotfix" == true ]]; then
  printf '%s\n' 'Owner-approved UI-only hotfix: no new database dump; existing recovery retained.' > "$backup_dir/database-backup-waiver.txt"
elif [[ "$version" == "1.8.0" ]]; then
  reuse_verified_dump
  printf '%s\n' 'Database recovery uses the verified pre-DDL V112 dump; this is not a post-DDL snapshot.'
else
  sudo -u postgres pg_dump --format=custom --lock-wait-timeout=10s next_ai_commerce > "$backup_dir/database.dump"
fi
# END VERIFIED BACKUP REUSE
if [[ "$ui_hotfix" != true ]]; then
  test -s "$backup_dir/database.dump"
  pg_restore --list "$backup_dir/database.dump" >/dev/null
  sha256sum "$backup_dir/database.dump"
fi
sha256sum "$backup_dir/previous.jar"
echo "Application-deployment recovery backup verified at $backup_dir (separate from the reviewed pre-DDL recovery set)."

# Version metadata and read-only recovery only. Preserve publishing and mail policies.
grep -Eq 'APP_BUILD_VERSION=[0-9]+\.[0-9]+\.[0-9]+' "$runtime_config"
rollback() {
  trap - ERR
  cp -p "$backup_dir/start" "$runtime_config"
  install -o nextaicommerce -g nextaicommerce -m 0640 "$backup_dir/previous.jar" "/opt/next-ai-commerce/releases/rollback-${revision:0:12}.jar"
  ln -sfn "/opt/next-ai-commerce/releases/rollback-${revision:0:12}.jar" /opt/next-ai-commerce/next-ai-commerce.jar
  systemctl restart next-ai-commerce
  echo "Deployment failed; previous application and environment restored. Database backup retained."
  exit 1
}
trap rollback ERR
# Preserve the existing TLS site and add WebSocket forwarding only for huddles.
# The endpoint still uses the application's session authentication and origin checks.
nginx_site=$(readlink -f /etc/nginx/sites-enabled/next-ai-commerce)
test -f "$nginx_site"
cp -p "$nginx_site" "$backup_dir/nginx-site"
restore_proxy() {
  cp -p "$backup_dir/nginx-site" "$nginx_site"
  nginx -t && systemctl reload nginx
}
if ! python3 - "$nginx_site" <<'PY'
import pathlib, sys
site = pathlib.Path(sys.argv[1])
source = site.read_text()
assert 'server_name app.nextaicommerce.com;' in source
if 'location = /ws/huddles {' not in source:
    anchor = ' location / {'
    assert source.count(anchor) == 1, 'Unexpected proxy layout; refusing to rewrite it'
    block = ''' location = /ws/huddles {
  proxy_pass http://127.0.0.1:8080;
  proxy_http_version 1.1;
  proxy_set_header Upgrade $http_upgrade;
  proxy_set_header Connection "upgrade";
  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
  proxy_read_timeout 90s;
  proxy_send_timeout 90s;
 }
'''
    site.write_text(source.replace(anchor, block + anchor, 1))
PY
then
  restore_proxy
  exit 1
fi
if ! nginx -t || ! systemctl reload nginx; then
  restore_proxy
  exit 1
fi
echo "Authenticated huddle WebSocket proxy validated and reloaded."
sed -i -E "s/APP_BUILD_VERSION=[0-9]+\.[0-9]+\.[0-9]+/APP_BUILD_VERSION=$version/" "$runtime_config"
# Preserve the separately reviewed runtime settings; no startup enqueue is enabled.
ln -sfn "$release_jar" /opt/next-ai-commerce/next-ai-commerce.jar
systemctl restart next-ai-commerce
healthy=false
for attempt in $(seq 1 36); do
  if systemctl is-active --quiet next-ai-commerce && curl --fail --silent --max-time 5 http://127.0.0.1:8080/login >/dev/null; then
    healthy=true
    break
  fi
  sleep 5
done
test "$healthy" = true
# Do not declare this release healthy if its additive profit/planning schema
# did not finish migrating. No business rows or credentials are printed.
migration_count=$(sudo -u postgres psql -d next_ai_commerce -XAtqc "SELECT count(*) FROM public.flyway_schema_history WHERE version IN ('91','92','93','94','95','96','97','98','99','100','101','102','103','104','105','106','107','108','109','110','111','112') AND success")
test "$migration_count" = 22
if [[ "$version" == "1.8.0" || "$ui_hotfix" == true ]]; then
  fee_schema_count=$(sudo -u postgres psql -d next_ai_commerce -XAtqc "SELECT count(*) FROM public.flyway_schema_history WHERE version IN ('113','114') AND success")
  test "$fee_schema_count" = 2
fi
echo "Profit, refund, cost-default and collaboration schema migrations V91–V112 verified."
trap - ERR
if [[ "$version" == "1.8.0" ]]; then
  echo "Release v$version staged; existing worker policies preserved. Fee collection awaits its separately reviewed bounded activation."
else
  echo "Release v$version completed; existing Amazon publishing policies unchanged."
fi
