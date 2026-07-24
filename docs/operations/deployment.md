# SpringSuite deployment

## Runtime layout

SpringSuite is deployed as a versioned runtime image. The release contains the JVM application, signed modules, launchers, scripts and native control-plane binaries.

Mutable state is preserved across deployments:

- `config/`
- `data/`
- `logs/`
- `.springsuite/`

The Windows runtime is split into two execution domains:

1. `suite-runtime-controller.exe` owns the JVM from Windows Session 0.
2. `suite-runtime-preloader.exe` shows a compact middle-screen startup popup driven by controller state; it starts at 0%, reaches 100% only after READY, and remains visible on failure.
3. `suite-runtime-tray.exe` owns notifications and the notification-area icon from the signed-in user's interactive session.

A service process must never create notification-area UI directly.

## Verification

Before publishing or applying a deployment image:

```powershell
.\gradlew.bat clean test verifyDeployLayout
```

The deploy manifest must contain the release version and SHA-256 inventory for every managed file.

## Canary

Run the candidate from a separate root and use ports that do not overlap production. Verify:

- application version;
- deployment identity;
- runtime root;
- JVM PID and supervisor PID;
- application status `READY`;
- management health `UP`;
- graceful shutdown;
- release of both application and management ports.

Canary processes must run headless so a deliberately failed startup cannot create a desktop crash dialog.

## Runtime-controller installation

Run the installer from a normal interactive terminal. In `Service` and `Portable` modes the tray is installed and started by default before the controller phase:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\install-runtime-controller.ps1 `
  -Mode Service `
  -Start
```

For a read-only check:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\install-runtime-controller.ps1 `
  -Mode Preflight
```

To install or repair only the current user's tray registration while remaining in preflight mode:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\install-runtime-controller.ps1 `
  -Mode Preflight `
  -InstallToast
```

`-InstallToast` is retained as a compatibility name; it installs the combined toast and tray user-session broker. Use `-NoTray` only for an explicitly headless installation.

The installer rejects tray installation from Session 0. It registers `suite-runtime-tray.exe` under the current user's `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, starts it immediately, and verifies that the process belongs to the same interactive Windows session. The process remains in the user session while SpringSuite is stopped, allowing the icon to show the stopped state.

| Color | Meaning |
|---|---|
| Green | Runtime is READY and the recorded controller/JVM PIDs are alive. |
| Blue | Update, rollback or restart handoff is in progress. |
| Yellow | Startup, probation, bootstrapping or a bounded wait is in progress. |
| Red | Runtime is stopped, failed, inaccessible, or a READY state references dead processes. |

Closing the tray indicator from its context menu does not stop SpringSuite. Running the interactive installer again or signing in again restarts the per-user indicator.

## Backups and logs

Replaced files are copied to:

```text
<target>\.springsuite\deploy-backups\yyyyMMdd-HHmmss\
```

Deployment logs are written to:

```text
<target>\.springsuite\deploy-staging\yyyyMMdd-HHmmss\apply.log
```

The staged payload is removed after a successful installation. Backups and logs are retained for rollback and diagnostics.

## Rollback

Stop SpringSuite, then copy the desired backup over the runtime root:

```powershell
$runtime = "C:\Users\Aiden\Documents\Take Some\NorthStar-Suite-V3"
$backup = "$runtime\.springsuite\deploy-backups\20260721-203000"

Copy-Item -Path "$backup\*" -Destination $runtime -Recurse -Force
& "$runtime\run.bat"
```

Mutable runtime state is not included in deploy backups because deployment does not replace it by default.

## Build-image verification

```powershell
.\gradlew.bat verifyDeployLayout
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\verify-repository.ps1 `
  -VerifyDeployImage
```

Both commands must succeed before a release package is published.
