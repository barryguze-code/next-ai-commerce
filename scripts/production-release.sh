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

# Install the exact CI-tested artifact without compiling beside the live application.
umask 077
package_dir=$(mktemp -d /opt/next-ai-commerce/releases/.download-XXXXXX)
package_file="$package_dir/application.jar"
curl --fail --location --silent --show-error --proto '=https' --proto-redir '=https' \
  --connect-timeout 15 --max-time 300 \
  "https://github.com/barryguze-code/next-ai-commerce/releases/download/build-${revision}/next-ai-commerce-${version}-${revision}.jar" \
  --output "$package_file"
printf '%s  %s\n' "$package_sha" "$package_file" | sha256sum --check --status
release_jar="/opt/next-ai-commerce/releases/next-ai-commerce-${version}-${revision:0:12}.jar"
install -o nextaicommerce -g nextaicommerce -m 0640 "$package_file" "$release_jar"

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

# Version metadata only. Do not change Amazon write, listing, shipping or mail policies.
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
sed -i -E "s/APP_BUILD_VERSION=[0-9]+\.[0-9]+\.[0-9]+/APP_BUILD_VERSION=$version/" "$runtime_config"
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
trap - ERR
echo "Release v$version completed; existing Amazon publishing policies unchanged."
