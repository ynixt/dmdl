#!/bin/sh
set -eu

: "${DMDL_ARIA2_RPC_SECRET:?DMDL_ARIA2_RPC_SECRET is required}"

# aria2's console log level. info and debug also log request URLs (debrid links can carry tokens).
log_level="${DMDL_ARIA2_LOG_LEVEL:-notice}"
case "$log_level" in
  debug|info|notice|warn|error) ;;
  *)
    echo "Invalid DMDL_ARIA2_LOG_LEVEL '$log_level': use one of debug, info, notice, warn, error" >&2
    exit 1
    ;;
esac

config_dir="${DMDL_ARIA2_CONFIG_DIR:-/config}"
mkdir -p "$config_dir" /downloads/.dmdl
touch "$config_dir/session.txt"

readout=/tmp/aria2-readout
rm -f "$readout"
mkfifo "$readout"
aria2_pid=
readout_pid=
printf '{}\n' > /downloads/.dmdl/aria2-allocation.json
cleanup() {
  if [ -n "$aria2_pid" ]; then kill "$aria2_pid" 2>/dev/null || true; wait "$aria2_pid" 2>/dev/null || true; fi
  if [ -n "$readout_pid" ]; then kill "$readout_pid" 2>/dev/null || true; wait "$readout_pid" 2>/dev/null || true; fi
  rm -f "$readout"
}
trap cleanup EXIT
trap 'exit 143' TERM INT

stdbuf -oL -eL aria2c \
  --enable-rpc=true \
  --rpc-listen-all="${DMDL_ARIA2_RPC_LISTEN_ALL:-true}" \
  --rpc-listen-port="${DMDL_ARIA2_RPC_PORT:-6800}" \
  --rpc-secret="${DMDL_ARIA2_RPC_SECRET}" \
  --dir="${DMDL_ARIA2_DEFAULT_DIR:-/tmp}" \
  --input-file="$config_dir/session.txt" \
  --save-session="$config_dir/session.txt" \
  --save-session-interval="${DMDL_ARIA2_SAVE_SESSION_INTERVAL:-30}" \
  --continue=true \
  --always-resume=true \
  --max-resume-failure-tries=0 \
  --max-concurrent-downloads=1 \
  --auto-file-renaming=false \
  --allow-overwrite=false \
  --console-log-level="$log_level" \
  --summary-interval=0 \
  --human-readable=false \
  --enable-color=false \
  --show-console-readout=true \
  --truncate-console-readout=false > "$readout" 2>&1 &
aria2_pid=$!
# mawk (Debian/Ubuntu) reads a pipe in large blocks unless interactive, which would hold aria2's
# messages and allocation updates back; busybox awk reads line by line and warns about -W.
awk_opts=
case "$(awk -W version 2>&1 < /dev/null || true)" in
  mawk*) awk_opts='-W interactive' ;;
esac
# shellcheck disable=SC2086
awk $awk_opts -v status=/downloads/.dmdl/aria2-allocation.json -f /usr/local/bin/allocation-readout.awk < "$readout" &
readout_pid=$!
wait "$aria2_pid"
aria2_pid=
