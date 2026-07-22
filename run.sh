#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
BOOTSTRAP="$ROOT/suiteBinaries/suite-runtime-bootstrap.exe"
CONFIG="$ROOT/config/runtime-controller.json"

if [ ! -f "$BOOTSTRAP" ]; then
  printf '%s\n' "[SpringSuite] runtime bootstrap is missing: $BOOTSTRAP" >&2
  exit 2
fi
if [ ! -f "$CONFIG" ]; then
  printf '%s\n' "[SpringSuite] controller config is missing: $CONFIG" >&2
  exit 3
fi

# Never launch spring-suite.jar directly. The controller is the sole runtime
# owner and enforces singleton, PID identity, health probation and rollback.
exec "$BOOTSTRAP" start --config "$CONFIG"
