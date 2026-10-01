#!/usr/bin/env bash
# Wgrywa (albo aktualizuje) kod serwera kont/licencji na VPS i restartuje usługę.
# Uruchamiane z komputera operatora (Git Bash), z folderu license-server:
#   bash deploy/push.sh ubuntu@51.68.136.151
# Wysyła tylko kod (src, admin - panel wsparcia, package*.json) - bez node_modules, danych i .env.
set -euo pipefail
HOST="${1:?Podaj serwer, np. ubuntu@51.68.136.151}"
cd "$(dirname "$0")/.."

tar -czf /tmp/license-server.tgz src admin package.json package-lock.json
scp -q /tmp/license-server.tgz "$HOST:/tmp/license-server.tgz"
ssh "$HOST" 'set -e
  sudo tar -xzf /tmp/license-server.tgz -C /opt/license-server
  sudo chown -R license:license /opt/license-server
  cd /opt/license-server && sudo -u license npm ci --omit=dev --no-audit --no-fund --silent
  sudo systemctl restart license-server
  sleep 2
  systemctl is-active license-server
  curl -s -o /dev/null -w "API lokalnie: %{http_code}\n" http://127.0.0.1:3000/api/catalog'
rm -f /tmp/license-server.tgz
