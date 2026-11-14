#!/bin/sh
# Starts as root only to fix ownership, then runs the server as PUID:PGID (the same convention as linuxserver.io images).
set -e
PUID="${PUID:-1000}"
PGID="${PGID:-1000}"
mkdir -p "${CONFIG_DIR:-/config}/profiles"
echo "mbrowser sync: user ${PUID}:${PGID}, timezone ${TZ:-UTC}, data in ${CONFIG_DIR:-/config}"
if [ "$(id -u)" = "0" ]; then
  chown -R "${PUID}:${PGID}" "${CONFIG_DIR:-/config}" 2>/dev/null || true
  exec setpriv --reuid="${PUID}" --regid="${PGID}" --clear-groups python -m app.main
fi
exec python -m app.main
