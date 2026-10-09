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

# Leave room for the package, rollback copy, database backup and live writes.
# Refuse early instead of exhausting the filesystem beside a running database.
database_bytes=$(sudo -u postgres psql -XAtqc "SELECT pg_database_size('next_ai_commerce')")
[[ "$database_bytes" =~ ^[0-9]+$ ]]
current_jar_bytes=$(stat -Lc %s /opt/next-ai-commerce/next-ai-commerce.jar)
required_bytes=$((database_bytes * 2 + current_jar_bytes * 3 + 1073741824))
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
sudo -u postgres pg_dump --format=custom --lock-wait-timeout=10s next_ai_commerce > "$backup_dir/database.dump"
test -s "$backup_dir/database.dump"
pg_restore --list "$backup_dir/database.dump" >/dev/null
sha256sum "$backup_dir/database.dump" "$backup_dir/previous.jar"
echo "Pre-release backup verified at $backup_dir"

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
# Restore the approved read-only, guarded history recovery. The original startup
# configuration is included in the rollback backup above.
if grep -Eq 'AMAZON_STARTUP_ORDER_RECONCILIATION_ENABLED=(true|false)' "$runtime_config"; then
  sed -i -E 's/AMAZON_STARTUP_ORDER_RECONCILIATION_ENABLED=(true|false)/AMAZON_STARTUP_ORDER_RECONCILIATION_ENABLED=true/g' "$runtime_config"
else
  echo 'Missing explicit startup order recovery setting; refusing to guess runtime configuration.' >&2
  false
fi
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
migration_count=$(sudo -u postgres psql -d next_ai_commerce -XAtqc "SELECT count(*) FROM public.flyway_schema_history WHERE version IN ('91','92','93','94','95','96','97','98','99','100','101','102','103') AND success")
test "$migration_count" = 13
echo "Profit, refund, cost-default and collaboration schema migrations V91–V103 verified."
trap - ERR
echo "Release v$version completed; existing Amazon publishing policies unchanged."
