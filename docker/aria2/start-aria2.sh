#!/bin/sh
set -eu

: "${DMDL_ARIA2_RPC_SECRET:?DMDL_ARIA2_RPC_SECRET is required}"

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
  --console-log-level=notice \
  --summary-interval=0 \
  --human-readable=false \
  --enable-color=false \
  --show-console-readout=true \
  --truncate-console-readout=false > "$readout" 2>&1 &
aria2_pid=$!
awk -v status=/downloads/.dmdl/aria2-allocation.json -f /usr/local/bin/allocation-readout.awk < "$readout" &
readout_pid=$!
wait "$aria2_pid"
aria2_pid=
