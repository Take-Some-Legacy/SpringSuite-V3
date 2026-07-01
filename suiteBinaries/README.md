# SpringSuite local binaries

This directory contains SpringSuite-owned sidecar executables used by the runtime.

Current binaries:

- `suite-cloudflared-wrapper.exe` — Go wrapper around `cloudflared` with Suite-local runtime/cache paths.
- `suite-repo-indexer.exe` — Go repository scanner/indexer that emits JSON for Suite.
- `suite-tail-watcher.exe` — Go tail/log watcher that streams file and log events.

Tool descriptors live under `tools/suite-binaries/**/tool.json` and resolve executables from this directory. SpringSuite must not depend on external descriptor roots by default.

Sources are vendored in `../goSrc`.
