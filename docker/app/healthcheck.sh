#!/bin/sh
set -eu

# The entrypoint may have generated the aria2 secret; docker exec does not inherit it.
ARIA2_RPC_SECRET="${ARIA2_RPC_SECRET:-$(cat /run/dmdl/aria2-rpc-secret)}"
export ARIA2_RPC_SECRET

/usr/local/bin/aria2-healthcheck
wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT:-8081}/actuator/health/readiness"
wget -q -O /dev/null http://127.0.0.1:8080/
