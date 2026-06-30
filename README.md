# SpringSuite
<p align="center">
  <img src=".github/SpringSuiteBanner.png" alt="NorthStar Engine banner" width="100%">
</p>

SpringSuite is the first Java Gradle Spring control-plane skeleton for the NOESIS / NorthStar operator workflow.

Current scope:

- Spring Boot application shell.
- Operator logging API and in-memory log stream.
- Cloudflared quick tunnel lifecycle API.
- Rolling application logs under `logs/`.

Future scope:

- Tool registry.
- Task runtime.
- Database-backed run history and project memory.
- Safe filesystem workspace policies.

## Run

```powershell
gradle :suite-app:bootRun
```

Application starts on:

```text
http://localhost:8090
```

## Enable cloudflared manually

Cloudflared is disabled by default. Start the app, then call:

```powershell
Invoke-RestMethod -Method Post http://localhost:8090/api/tunnel/cloudflared/start
```

Or enable autostart:

```powershell
$env:SUITE_CLOUDFLARED_ENABLED="true"
$env:SUITE_CLOUDFLARED_AUTO_START="true"
gradle :suite-app:bootRun
```

Cloudflared command used by default:

```text
cloudflared tunnel --url http://localhost:8090 --no-autoupdate
```

## APIs

```text
GET  /api/system/status
GET  /api/operator/logs
POST /api/operator/logs
GET  /api/operator/logs/stream
GET  /api/tunnel/cloudflared/status
POST /api/tunnel/cloudflared/start
POST /api/tunnel/cloudflared/stop
POST /api/tunnel/cloudflared/restart
GET  /api/tunnel/cloudflared/logs
GET  /actuator/health
```
