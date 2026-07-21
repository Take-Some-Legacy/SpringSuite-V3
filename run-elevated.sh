#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p logs/crash logs/archive data .springsuite
java '-XX:ErrorFile=logs/crash/hs_err_pid%p.log' -XX:+ShowCodeDetailsInExceptionMessages -jar spring-suite.jar --elevated --server.address=0.0.0.0 --suite.cloudflared.enabled=false --suite.cloudflared.auto-start=false "$@"
