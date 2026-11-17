#!/usr/bin/env bash
# Runs aria2, the backend and nginx in one container.
# If any of them exits, the others are stopped and the container exits so the restart policy applies.
set -uo pipefail

# The container starts as root to prepare /run, /data and the staging folder, then runs aria2, the
# backend and the log collectors as DMDL_PUID:DMDL_PGID, so downloaded files belong to that host user.
# nginx stays root (its workers drop to www-data) and never touches /media.
if [ "$(id -u)" != 0 ]; then
  echo "dmdl must start as root: remove 'user:' from the service and set DMDL_PUID / DMDL_PGID instead" >&2
  exit 1
fi
puid="${DMDL_PUID:-0}"
pgid="${DMDL_PGID:-$puid}"
file_umask="${DMDL_UMASK:-022}"
for value in "$puid" "$pgid"; do
  case "$value" in
    '' | *[!0-9]*) echo "Invalid DMDL_PUID/DMDL_PGID '$value': use a numeric id" >&2; exit 1 ;;
  esac
done
case "$file_umask" in
  [0-7][0-7][0-7] | 0[0-7][0-7][0-7]) ;;
  *) echo "Invalid DMDL_UMASK '$file_umask': use an octal value such as 022 or 002" >&2; exit 1 ;;
esac
umask "$file_umask"

# Runs a command as DMDL_PUID:DMDL_PGID. setpriv execs in place, so $! stays the service's PID.
run_as() {
  if [ "$puid" = 0 ] && [ "$pgid" = 0 ]; then
    "$@"
  else
    setpriv --reuid="$puid" --regid="$pgid" --clear-groups -- "$@"
  fi
}

# aria2 RPC only listens on loopback, but it can still write files anywhere in the container,
# so it keeps a secret. Without an explicit one, a random secret is generated on every start
# and shared with the backend (env) and the healthcheck (root-only file).
if [ -z "${DMDL_ARIA2_RPC_SECRET:-}" ]; then
  DMDL_ARIA2_RPC_SECRET="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
fi
export DMDL_ARIA2_RPC_SECRET
# /run/dmdl can be traversed but not listed; each entry protects itself (the secret is root-only,
# logs/ and aria2/ belong to the service user).
install -d -m 0711 /run/dmdl
chmod 0711 /run/dmdl
(umask 077 && printf '%s' "$DMDL_ARIA2_RPC_SECRET" > /run/dmdl/aria2-rpc-secret)
# Holds the marker the backend writes to ask the aria2 supervisor for a restart.
install -d -m 0700 -o "$puid" -g "$pgid" /run/dmdl/aria2 || exit 1
# A readout FIFO left by a previous run of this container (docker restart keeps /tmp).
rm -f /tmp/aria2-readout

# aria2 and the backend write the database, the aria2 session and the staging folder.
if [ "$puid" != 0 ] || [ "$pgid" != 0 ]; then
  mkdir -p /data /media/.dmdl || exit 1
  chown -R "$puid:$pgid" /data /media/.dmdl || exit 1
  if ! run_as test -w /media; then
    echo "/media is not writable by DMDL_PUID=$puid / DMDL_PGID=$pgid; give that user write access to the mounted media folder" >&2
    exit 1
  fi
fi

# nginx access log: off by default (the UI polls and streams constantly), one line per request
# on stdout with DMDL_NGINX_ACCESS_LOG=true. Included from the server block of nginx.conf.
if [ "${DMDL_NGINX_ACCESS_LOG:-false}" = "true" ]; then
  printf 'access_log /var/log/nginx/access.log;\n' > /run/dmdl/nginx-access-log.conf
else
  printf 'access_log off;\n' > /run/dmdl/nginx-access-log.conf
fi

# Internal wiring of the image. These are fixed on purpose and are not configuration:
# nginx listens on 8080, the backend on 127.0.0.1:8081 and aria2 RPC on 127.0.0.1:6800.
# /data holds the database and the aria2 session, /media is the mounted library and
# /downloads is a symlink to /media, so aria2 and the backend see the same staging files.
aria2_rpc_port=6800
backend_port=8081

aria2_pid=
backend_pid=
nginx_pid=
readiness_pid=
collector_pids=()

/usr/local/bin/dmdl-log-collector --initialize || exit 1
# The backend reads the captured logs; collectors run as the same user so it can.
chown -R "$puid:$pgid" /run/dmdl/logs || exit 1
for source in backend nginx aria2; do
  mkfifo "/run/dmdl/logs/$source.pipe" || exit 1
  run_as /usr/local/bin/dmdl-log-collector --source "$source" < "/run/dmdl/logs/$source.pipe" &
  collector_pids+=("$!")
done

install -d -m 0755 /run/dmdl-status || exit 1
write_backend_state() {
  (umask 022 && printf '{"code":"BACKEND_UNAVAILABLE","state":"%s"}\n' "$1" > /run/dmdl-status/backend-state.json.tmp) &&
    mv /run/dmdl-status/backend-state.json.tmp /run/dmdl-status/backend-state.json
}
write_backend_state starting || exit 1

stop_all() {
  # Reverse start order: stop serving traffic first, aria2 last.
  for pid in "$nginx_pid" "$readiness_pid" "$backend_pid" "$aria2_pid"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  # A collector can still be waiting to open its FIFO if startup stopped before its service.
  if [ -z "$backend_pid" ]; then kill -TERM "${collector_pids[0]}" 2>/dev/null || true; fi
  if [ -z "$nginx_pid" ]; then kill -TERM "${collector_pids[1]}" 2>/dev/null || true; fi
  if [ -z "$aria2_pid" ]; then kill -TERM "${collector_pids[2]}" 2>/dev/null || true; fi
  # Services have closed their writers. Let collectors drain before shutting them down.
  for pid in "${collector_pids[@]}"; do
    # A killed nginx master can leave workers holding a writer open until container exit.
    # Bound the drain so these inherited descriptors cannot keep the supervisor alive.
    timeout 2s tail --pid="$pid" --sleep-interval=0.1 -f /dev/null 2>/dev/null || true
    if kill -0 "$pid" 2>/dev/null; then kill -TERM "$pid" 2>/dev/null || true; fi
    wait "$pid" 2>/dev/null || true
  done
}

trap 'stop_all; exit 143' TERM INT

DMDL_ARIA2_RPC_PORT="$aria2_rpc_port" \
DMDL_ARIA2_RPC_LISTEN_ALL=false \
DMDL_ARIA2_CONFIG_DIR=/data/aria2 \
DMDL_ARIA2_DEFAULT_DIR=/tmp \
DMDL_ARIA2_RESTART_MARKER=/run/dmdl/aria2/restart-requested \
  run_as /usr/local/bin/start-aria2 > /run/dmdl/logs/aria2.pipe 2>&1 &
aria2_pid=$!

for _ in $(seq 1 60); do
  if DMDL_ARIA2_RPC_PORT="$aria2_rpc_port" /usr/local/bin/aria2-healthcheck 2>/dev/null; then break; fi
  if ! kill -0 "$aria2_pid" 2>/dev/null; then
    echo "aria2 exited during startup" >&2
    stop_all
    exit 1
  fi
  sleep 1
done

# DMDL_JAVA_OPTS is intentionally word-split into separate JVM options.
# shellcheck disable=SC2086
run_as java --enable-native-access=ALL-UNNAMED ${DMDL_JAVA_OPTS:-} -jar /app/app.jar \
  --server.address=127.0.0.1 \
  --server.port="$backend_port" \
  --application.database.path=/data/dmdl.db \
  --application.storage.root=/media \
  --application.aria2.rpc-url="http://127.0.0.1:$aria2_rpc_port/jsonrpc" \
  --application.aria2.application-staging-root=/media/.dmdl \
  --application.aria2.daemon-staging-root=/downloads/.dmdl \
  --application.aria2.startup-options-file=/data/aria2/startup-options.conf \
  --application.aria2.restart-marker-file=/run/dmdl/aria2/restart-requested \
  > /run/dmdl/logs/backend.pipe 2>&1 &
backend_pid=$!

(
  until wget -q -T 2 -O /dev/null "http://127.0.0.1:$backend_port/actuator/health/readiness"; do
    sleep 1
  done
  write_backend_state ready
) &
readiness_pid=$!

nginx -g 'daemon off;' > /run/dmdl/logs/nginx.pipe 2>&1 &
nginx_pid=$!

wait -n "$aria2_pid" "$backend_pid" "$nginx_pid" "${collector_pids[@]}"
status=$?
echo "A service exited with status $status; stopping the container" >&2
stop_all
exit "$status"
