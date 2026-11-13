#!/bin/sh
set -eu

# The entrypoint may have generated the aria2 secret; docker exec does not inherit it.
DMDL_ARIA2_RPC_SECRET="${DMDL_ARIA2_RPC_SECRET:-$(cat /run/dmdl/aria2-rpc-secret)}"
DMDL_ARIA2_RPC_PORT=6800
export DMDL_ARIA2_RPC_SECRET DMDL_ARIA2_RPC_PORT

/usr/local/bin/aria2-healthcheck
wget -q -O /dev/null http://127.0.0.1:8081/actuator/health/readiness
wget -q -O /dev/null http://127.0.0.1:8080/
