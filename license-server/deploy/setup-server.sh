#!/usr/bin/env bash
# Jednorazowa instalacja VPS pod serwer kont/licencji i aktualizacje PluginManagera.
# Uruchamiane na serwerze (Ubuntu 24.04/26.04):  sudo bash setup-server.sh <domena-api> <domena-download> [domena-strony]
# np.  sudo bash setup-server.sh api.rsmc-network.pl download.rsmc-network.pl
#
# Co robi:
#   - aktualizacje systemu + automatyczne łatki bezpieczeństwa
#   - zapora: tylko SSH (22), HTTP (80), HTTPS (443); fail2ban na SSH
#   - Node.js (z repozytorium Ubuntu), Caddy (HTTPS z Let's Encrypt automatycznie)
#   - użytkownik systemowy "license" i usługa systemd license-server (wstaje sama po restarcie)
#   - /var/www/download - instalator i pliki aktualizacji aplikacji
#   - codzienna kopia danych (konta, licencje) do /var/backups/license-server, 30 dni wstecz
# Kod serwera wgrywa osobno push.sh (z komputera operatora). Skrypt można uruchomić
# ponownie - niczego nie psuje (nie nadpisuje .env ani danych).
set -euo pipefail

API_HOST="${1:?Podaj domenę API, np. api.rsmc-network.pl}"
DL_HOST="${2:?Podaj domenę pobierania, np. download.rsmc-network.pl}"
SITE_HOST="${3:-rsmc-network.pl}"
APP_DIR=/opt/license-server
DATA_DIR=/var/lib/license-server
DL_DIR=/var/www/download
SITE_DIR=/var/www/site
BACKUP_DIR=/var/backups/license-server

export DEBIAN_FRONTEND=noninteractive
echo "== System"
apt-get update -y
apt-get upgrade -y
apt-get install -y ca-certificates curl ufw fail2ban unattended-upgrades nodejs npm caddy
dpkg-reconfigure -f noninteractive unattended-upgrades

echo "== Zapora"
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable
systemctl enable --now fail2ban

echo "== Użytkownik i katalogi"
id license >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin license
mkdir -p "$APP_DIR" "$DATA_DIR" "$DL_DIR" "$SITE_DIR" "$BACKUP_DIR"
chown -R license:license "$APP_DIR" "$DATA_DIR"
chmod 750 "$DATA_DIR"
# katalog pobierania: zapisuje operator (ubuntu) przy wydaniu, czyta Caddy
chown -R ubuntu:ubuntu "$DL_DIR"

# .env tylko przy pierwszej instalacji - losowy sekret admina, bez trybu testowego
if [ ! -f "$APP_DIR/.env" ]; then
  cat > "$APP_DIR/.env" <<EOF
PORT=3000
ADMIN_SECRET=$(openssl rand -hex 32)
EOF
  chown license:license "$APP_DIR/.env"
  chmod 600 "$APP_DIR/.env"
fi

echo "== Usługa systemd"
cat > /etc/systemd/system/license-server.service <<EOF
[Unit]
Description=RSMC license/account server
After=network.target

[Service]
User=license
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/.env
# dane (konta, licencje, sesje) poza katalogiem kodu - wgranie nowej wersji ich nie rusza
ExecStartPre=/bin/sh -c 'test -e $APP_DIR/data || ln -s $DATA_DIR $APP_DIR/data'
ExecStart=/usr/bin/node src/server.js
Restart=always
RestartSec=3
NoNewPrivileges=true
ProtectSystem=full
ReadWritePaths=$DATA_DIR $APP_DIR

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable license-server

echo "== Caddy (HTTPS)"
cat > /etc/caddy/Caddyfile <<EOF
$API_HOST {
	encode gzip
	reverse_proxy 127.0.0.1:3000
}

$DL_HOST {
	encode gzip
	root * $DL_DIR
	file_server
	# plik z informacją o najnowszej wersji - bez zapamiętywania, żeby aktualizacja była widoczna od razu
	@latest path /latest.json
	header @latest Cache-Control "no-cache"
	header @latest Access-Control-Allow-Origin "*"
}

# strona-wizytówka aplikacji (pliki z PluginManager/website, wgrywa website/deploy.sh)
$SITE_HOST {
	encode gzip zstd
	root * $SITE_DIR
	file_server
	@assets path /img/* /*.css /*.js
	header @assets Cache-Control "public, max-age=86400"
}

www.$SITE_HOST {
	redir https://$SITE_HOST{uri} permanent
}
EOF
systemctl enable caddy
systemctl reload caddy || systemctl restart caddy

echo "== Codzienna kopia danych"
cat > /usr/local/bin/license-backup <<EOF
#!/bin/sh
# kopia kont/licencji; trzymamy 30 dni
tar -czf $BACKUP_DIR/data-\$(date +%F).tar.gz -C $DATA_DIR .
find $BACKUP_DIR -name 'data-*.tar.gz' -mtime +30 -delete
EOF
chmod 755 /usr/local/bin/license-backup
echo "15 3 * * * root /usr/local/bin/license-backup" > /etc/cron.d/license-backup

[ -f "$DL_DIR/index.html" ] || echo '<!doctype html><meta charset="utf-8"><title>RSMC</title><p>Wkrótce.</p>' > "$DL_DIR/index.html"
chown -R ubuntu:ubuntu "$DL_DIR" "$SITE_DIR"

echo
echo "Gotowe. Wgraj kod: push.sh z komputera operatora. Sekret admina: sudo cat $APP_DIR/.env"
