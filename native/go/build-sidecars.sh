#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

sh "$SCRIPT_DIR/suite-cloudflared-wrapper-go/scripts/build.sh"
sh "$SCRIPT_DIR/suite-fs-worker-go/scripts/build.sh"
sh "$SCRIPT_DIR/suite-repo-indexer-go/scripts/build.sh"
sh "$SCRIPT_DIR/suite-tail-watcher-go/scripts/build.sh"
sh "$SCRIPT_DIR/suite-desktop-capture-go/scripts/build.sh"
