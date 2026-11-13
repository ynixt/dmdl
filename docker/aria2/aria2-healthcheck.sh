#!/bin/sh
set -eu

payload="{\"jsonrpc\":\"2.0\",\"id\":\"health\",\"method\":\"aria2.getVersion\",\"params\":[\"token:${ARIA2_RPC_SECRET}\"]}"
wget -q -O - --header='Content-Type: application/json' --post-data="$payload" "http://127.0.0.1:${ARIA2_RPC_PORT:-6800}/jsonrpc" | grep -q '"result"'
