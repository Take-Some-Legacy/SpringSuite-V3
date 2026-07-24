# SpringSuite local binaries

This directory contains SpringSuite-owned native executables shipped with the runtime image.

## Runtime control plane

- `suite-runtime-controller.exe` — owns the JVM process tree, health probation, graceful restart and crash recovery.
- `suite-runtime-replacer.exe` — performs offline transactional replacement and verified rollback.
- `suite-runtime-bootstrap.exe` — starts or replaces control-plane binaries without letting a process overwrite itself.
- `suite-runtime-preloader.exe` — compact middle-screen startup popup driven by controller-published lifecycle progress.
- `suite-runtime-toast.exe` — console CLI for user-session notification installation and diagnostics.
- `suite-runtime-tray.exe` — GUI notification broker and color-coded SpringSuite tray status indicator.
- `suite-runtime-toast-host.exe` — native WinToast notification host.

The control-plane sources live in the sibling repository `../suite-runtime-controller-go`. WinToast lives in the sibling upstream checkout `../WinToast`.

## Runtime sidecars

- `suite-cloudflared-wrapper.exe` — Go wrapper around `cloudflared` with Suite-local runtime/cache paths.
- `suite-desktop-agent.exe` — Windows desktop/UI Automation agent.
- `suite-desktop-capture.exe` — Windows desktop capture helper.
- `suite-fs-worker.exe` — isolated filesystem worker.
- `suite-repo-indexer.exe` — repository scanner/indexer that emits JSON for Suite.
- `suite-tail-watcher.exe` — tail/log watcher that streams file and log events.

Tool descriptors under `tools/suite-binaries/**/tool.json` resolve sidecar executables from this directory. The runtime must not depend on external descriptor roots by default.
