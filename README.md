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

## OpenAI integration

SpringSuite includes the `suite-openai` module for server-side OpenAI access. It keeps secrets outside Java code and resolves bearer credentials in this order when `suite.openai.auth.mode=auto`:

1. OpenAI Workload Identity Federation, when `OPENAI_EXTERNAL_OIDC_JWT`, `OPENAI_IDENTITY_PROVIDER_ID`, and `OPENAI_SERVICE_ACCOUNT_ID` are available.
2. `OPENAI_ACCESS_TOKEN`, for externally managed short-lived access tokens.
3. `OPENAI_API_KEY`, used as the bearer credential fallback.

Runtime endpoints:

- `GET /api/openai/status` — credential source, fingerprint, cache path, expiry and refresh window; no secret is returned.
- `POST /api/openai/auth/refresh` — forces workload-identity token refresh and updates the app-token cache.
- `POST /api/openai/responses` — sends a Responses API request through the configured token provider.

Console commands:

- `openai status`
- `openai setup` — opens the local browser binding page.
- `openai refresh`
- `openai ask <prompt>`

Browser setup:

- `GET /openai/setup` — local-only HTML page for first OpenAI credential binding.
- `POST /api/openai/link/api-key` — stores a local API key credential on the server side.
- `POST /api/openai/link/unlink` — removes the local credential.

The workload-identity token cache defaults to `authority/openai/app_access_token.json` under the Suite runtime root. The browser-linked local credential defaults to `authority/openai/local_credentials.json`.

## Provider-agnostic AI service

SpringSuite 0.2 introduces a provider-agnostic AI facade. Core contracts live in `suite-core` under `com.takesome.springsuite.core.ai`; runtime routing lives in `suite-ai`; provider implementations live in modules.

Key endpoints:

- `GET /api/ai/providers` — list registered AI providers.
- `GET /api/ai/status?provider=<id>` — inspect provider credentials without revealing secrets.
- `POST /api/ai/chat` — send a provider-agnostic chat request.

Console commands:

- `ai providers`
- `ai default`
- `ai status [provider]`
- `ai ask [--provider id] [--model id] <prompt>`
- `ai setup <provider>`

Built-in providers:

- `openai` — module-backed adapter over `suite-openai` and the OpenAI Responses API.
- `zai` — configurable OpenAI Chat Completions compatible adapter for GLM, defaulting to `glm-5.2` and `ZAI_API_KEY`.
- `local-openai-compatible` — disabled template for local/self-hosted OpenAI-compatible endpoints such as vLLM, SGLang, LM Studio or Ollama-compatible servers.

The older `/api/openai/*` endpoints and `openai` console command remain available as compatibility surfaces.
