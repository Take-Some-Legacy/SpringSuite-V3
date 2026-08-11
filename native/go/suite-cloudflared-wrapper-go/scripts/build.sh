#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MODULE_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
REPO_ROOT=$(CDPATH= cd -- "$MODULE_DIR/../../.." && pwd)
OUT_DIR="$REPO_ROOT/suiteBinaries"
BIN_NAME="suite-cloudflared-wrapper"

cd "$MODULE_DIR"
mkdir -p build "$OUT_DIR"

go test ./...
go build -trimpath -ldflags "-s -w" -o "build/$BIN_NAME" ./cmd/$BIN_NAME
cp "build/$BIN_NAME" "$OUT_DIR/$BIN_NAME"
chmod +x "build/$BIN_NAME" "$OUT_DIR/$BIN_NAME"

printf "[OK] built %s -> %s\n" "$BIN_NAME" "$OUT_DIR/$BIN_NAME"
