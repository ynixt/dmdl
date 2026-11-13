#!/usr/bin/env bash
# Runs aria2, the backend and nginx in one container.
# If any of them exits, the others are stopped and the container exits so the restart policy applies.
set -uo pipefail

# aria2 RPC only listens on loopback, but it can still write files anywhere in the container,
# so it keeps a secret. Without an explicit one, a random secret is generated on every start
# and shared with the backend (env) and the healthcheck (root-only file).
if [ -z "${DMDL_ARIA2_RPC_SECRET:-}" ]; then
  DMDL_ARIA2_RPC_SECRET="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
fi
export DMDL_ARIA2_RPC_SECRET
install -d -m 0700 /run/dmdl
(umask 077 && printf '%s' "$DMDL_ARIA2_RPC_SECRET" > /run/dmdl/aria2-rpc-secret)

# Internal wiring of the image. These are fixed on purpose and are not configuration:
# nginx listens on 8080, the backend on 127.0.0.1:8081 and aria2 RPC on 127.0.0.1:6800.
# /data holds the database and the aria2 session, /media is the mounted library and
# /downloads is a symlink to /media, so aria2 and the backend see the same staging files.
aria2_rpc_port=6800
backend_port=8081

aria2_pid=
backend_pid=
nginx_pid=

stop_all() {
  # Reverse start order: stop serving traffic first, aria2 last.
  for pid in "$nginx_pid" "$backend_pid" "$aria2_pid"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
}

trap 'stop_all; exit 143' TERM INT

DMDL_ARIA2_RPC_PORT="$aria2_rpc_port" \
DMDL_ARIA2_RPC_LISTEN_ALL=false \
DMDL_ARIA2_CONFIG_DIR=/data/aria2 \
DMDL_ARIA2_DEFAULT_DIR=/tmp \
  /usr/local/bin/start-aria2 &
aria2_pid=$!

for _ in $(seq 1 60); do
  if DMDL_ARIA2_RPC_PORT="$aria2_rpc_port" /usr/local/bin/aria2-healthcheck 2>/dev/null; then break; fi
  if ! kill -0 "$aria2_pid" 2>/dev/null; then
    echo "aria2 exited during startup" >&2
    exit 1
  fi
  sleep 1
done

# DMDL_JAVA_OPTS is intentionally word-split into separate JVM options.
# shellcheck disable=SC2086
java --enable-native-access=ALL-UNNAMED ${DMDL_JAVA_OPTS:-} -jar /app/app.jar \
  --server.address=127.0.0.1 \
  --server.port="$backend_port" \
  --application.database.path=/data/dmdl.db \
  --application.storage.root=/media \
  --application.aria2.rpc-url="http://127.0.0.1:$aria2_rpc_port/jsonrpc" \
  --application.aria2.application-staging-root=/media/.dmdl \
  --application.aria2.daemon-staging-root=/downloads/.dmdl &
backend_pid=$!

nginx -g 'daemon off;' &
nginx_pid=$!

wait -n "$aria2_pid" "$backend_pid" "$nginx_pid"
status=$?
echo "A service exited with status $status; stopping the container" >&2
stop_all
exit "$status"
