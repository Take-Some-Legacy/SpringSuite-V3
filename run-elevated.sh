#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
java -jar spring-suite.jar --elevated --server.address=0.0.0.0 --suite.cloudflared.enabled=false --suite.cloudflared.auto-start=false "$@"
