#!/usr/bin/env bash
# Wgrywa PŁATNE jary pluginów (z ../dist po mvn package) na VPS, do folderu, z którego
# serwer licencji wydaje je klientom z licencją (GET /api/me/plugins/:id/download).
# Darmowe pluginy są wbudowane w instalator PluginManagera - tu ich nie wysyłamy.
# Uruchamiane z komputera operatora (Git Bash), z folderu license-server:
#   bash deploy/push-jars.sh ubuntu@51.68.136.151
set -euo pipefail
HOST="${1:?Podaj serwer, np. ubuntu@51.68.136.151}"
cd "$(dirname "$0")/.."

# Musi się zgadzać z FREE_PLUGIN_IDS w PluginManager/desktop-app/src/lib/freePlugins.ts
# i z listą JARS w desktop-app/src-tauri/src/embedded_jars.rs.
FREE="core announcer farming menu teleport chatfilter hud ranks generators"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
for jar in ../dist/mainplugins-*.jar; do
  id="$(basename "$jar" | sed -E 's/^mainplugins-([a-z0-9]+)-.*\.jar$/\1/')"
  case " $FREE " in *" $id "*) continue ;; esac
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
  ls /opt/license-server/plugin-jars | wc -l | xargs echo "Jars on the server:"'
rm -f /tmp/plugin-jars.tgz
