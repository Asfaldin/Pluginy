#!/usr/bin/env bash
# Wgrywa PŁATNE jary pluginów (z ../dist po mvn package) na VPS, do folderu, z którego
# serwer licencji wydaje je klientom z licencją (GET /api/me/plugins/:id/download).
# Darmowe pluginy są wbudowane w instalator PluginManagera - tu ich nie wysyłamy.
# Uruchamiane z komputera operatora (Git Bash), z folderu license-server:
#   bash deploy/push-jars.sh ubuntu@51.68.136.151
set -euo pipefail
HOST="${1:?Podaj serwer, np. ubuntu@51.68.136.151}"
cd "$(dirname "$0")/.."

# Tylko te płatne pluginy - musi się zgadzać z listą PAID w
# PluginManager/desktop-app/src-tauri/src/embedded_jars.rs. Jawna lista zamiast "wszystko
# poza darmowymi": w dist/ potrafią leżeć stare jary usuniętych modułów.
PAID="blocks crates dungeons fishing market mobs quests redstone shop skyblock spawn spawners tools"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
for id in $PAID; do
  jar=$(ls ../dist/mainplugins-"$id"-*.jar 2>/dev/null | head -1)
  [ -n "$jar" ] || { echo "Missing ../dist/mainplugins-$id-*.jar - run ./mvnw package first."; exit 1; }
  cp "$jar" "$STAGE/"
  echo "  + $(basename "$jar")"
done

tar -czf /tmp/plugin-jars.tgz -C "$STAGE" .
scp -q /tmp/plugin-jars.tgz "$HOST:/tmp/plugin-jars.tgz"
ssh "$HOST" 'set -e
  sudo mkdir -p /opt/license-server/plugin-jars
  sudo rm -f /opt/license-server/plugin-jars/*.jar
  sudo tar -xzf /tmp/plugin-jars.tgz -C /opt/license-server/plugin-jars
  sudo chown -R license:license /opt/license-server/plugin-jars
  sudo chmod 750 /opt/license-server/plugin-jars
  rm -f /tmp/plugin-jars.tgz
  sudo ls /opt/license-server/plugin-jars | wc -l | xargs echo "Jars on the server:"'
rm -f /tmp/plugin-jars.tgz
