#!/usr/bin/env bash
# Runs aria2, the backend and nginx in one container.
# If any of them exits, the others are stopped and the container exits so the restart policy applies.
set -uo pipefail

# aria2 RPC only listens on loopback, but it can still write files anywhere in the container,
# so it keeps a secret. Without an explicit one, a random secret is generated on every start
# and shared with the backend (env) and the healthcheck (root-only file).
if [ -z "${ARIA2_RPC_SECRET:-}" ]; then
  ARIA2_RPC_SECRET="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
fi
export ARIA2_RPC_SECRET
install -d -m 0700 /run/dmdl
(umask 077 && printf '%s' "$ARIA2_RPC_SECRET" > /run/dmdl/aria2-rpc-secret)

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

/usr/local/bin/start-aria2 &
aria2_pid=$!

for _ in $(seq 1 60); do
  if /usr/local/bin/aria2-healthcheck 2>/dev/null; then break; fi
  if ! kill -0 "$aria2_pid" 2>/dev/null; then
    echo "aria2 exited during startup" >&2
    exit 1
  fi
  sleep 1
done

java --enable-native-access=ALL-UNNAMED ${JAVA_OPTS:-} -jar /app/app.jar &
backend_pid=$!

nginx -g 'daemon off;' &
nginx_pid=$!

wait -n "$aria2_pid" "$backend_pid" "$nginx_pid"
status=$?
echo "A service exited with status $status; stopping the container" >&2
stop_all
exit "$status"
