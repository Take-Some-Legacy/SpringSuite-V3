# Deployment

SpringSuite deployment is performed by `scripts/deploy.ps1`. The script builds a verified deploy image, stages it inside the target runtime, preserves mutable runtime state, requests a graceful restart through the local MCP bridge and applies the update only after the old JVM exits.

## Standard deployment

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\deploy.ps1 `
  -Target "C:\Users\Aiden\Documents\Take Some\NorthStar-Suite-V3"
```

When `-Target` is omitted, the script resolves the destination in this order:

1. Explicit `-Target` argument.
2. `SPRING_SUITE_RUNTIME` environment variable.
3. `%USERPROFILE%\Documents\Take Some\NorthStar-Suite-V3`.

## Deployment pipeline

```text
clean → test → assembleDeploy → verifyDeployLayout
      → stage payload under target/.springsuite
      → request SpringSuite restart through local MCP
      → wait for old JVM
      → back up replaced files
      → install payload
      → launch run.bat
      → wait for /actuator/health = UP
```

The following target directories are preserved by default:

- `config/`
- `data/`
- `logs/`
- `.springsuite/`

The signed `modules/` payload, application JAR, launchers, static assets, browser extension, native sidecars, tool descriptors and operational scripts are updated.

## Options

| Option | Behavior |
|---|---|
| `-SkipTests` | Builds without executing the test suite. |
| `-SkipBuild` | Uses the existing `build/deploy` image. |
| `-NoStart` | Applies the payload but leaves the runtime stopped. |
| `-ReplaceConfig` | Replaces target configuration with the deploy image configuration. |
| `-ForceStop` | Force-stops an active JVM only when graceful MCP restart is unavailable. |
| `-KeepBuild` | Keeps Gradle build output after a successful deployment. |
| `-Port 8090` | Sets the local management port used for status and health checks. |
| `-WhatIf` | Shows the target operation without staging or applying files. |

## Authentication for graceful restart

The deployment script resolves the MCP bridge token from:

1. `NORTHSTAR_BRIDGE_ACCESS_TOKEN`.
2. `%LOCALAPPDATA%\NoesisSuite\authority\bridge_access_token.txt`.
3. `<target>\authority\bridge_access_token.txt`.

Without a token, an active runtime is not terminated unless `-ForceStop` is explicitly supplied.

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
