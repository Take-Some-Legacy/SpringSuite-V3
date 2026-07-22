# SpringSuite
<p align="center">
  <img src=".github/SpringSuiteBanner.png" alt="NorthStar Engine banner" width="100%">
</p>

SpringSuite is a Java 17 control plane for the NOESIS / NorthStar operator workflow. It combines a Spring Boot runtime, MCP/OAuth bridge, bounded workspace access, tool execution, persistent diagnostics, desktop/browser assistance, signed feature modules and a managed Cloudflare Tunnel lifecycle.

## Capabilities

- Provider-neutral AI routing with OpenAI, Ollama and compatible backends.
- MCP agent bridge with scoped authorization and audited tool execution.
- Bounded repository workspace operations and external toolbelt discovery.
- SQL-backed request journal, diagnostics and crash reporting.
- Desktop/browser context ingestion with guarded action planning.
- Signed runtime modules and native Go sidecars.
- Self-managed named Cloudflare Tunnel started after the local HTTP server is ready.

## Repository

The repository is a multi-project Gradle build. Java modules remain at the root so Gradle project paths, module descriptors and deployment tasks stay explicit. Operational scripts live in `scripts/`; documentation is grouped under `docs/architecture`, `docs/integrations` and `docs/operations`.

- [Documentation index](docs/README.md)
- [Repository layout](docs/architecture/repository-layout.md)
- [Deployment guide](docs/operations/deployment.md)
- [Observability and metrics](docs/operations/observability.md)

## Build and run

Use the checked-in Gradle wrapper; a system Gradle installation is not required.

```powershell
.\gradlew.bat test
.\gradlew.bat :suite-app:bootRun
```

The runtime listens on:

```text
http://localhost:8090
```

Build and deploy a production runtime with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\deploy.ps1 `
  -Target "C:\Users\Aiden\Documents\Take Some\NorthStar-Suite-V3"
```

## MCP runtime availability guard

SpringSuite keeps remote MCP/API control available by refusing `exit`, `quit`, and `shutdown` commands from non-console callers by default. This prevents an agent-side update script from terminating the origin while an MCP response is still traversing cloudflared, which otherwise surfaces as `EOF` and may cause the client platform to temporarily disable the connector. Local interactive-console shutdown remains available. To deliberately permit remote shutdown, set `suite.console.command.allow-shutdown-over-api=true` and use an external supervisor to bring the runtime back.

## Managed Cloudflare Tunnel

Cloudflared is enabled and started automatically by `CloudflaredTunnelService` on `ApplicationReadyEvent`. SpringSuite owns the wrapper process, runtime directory, PID, logs and shutdown lifecycle. The default named-tunnel invocation is equivalent to:

```text
cloudflared tunnel --no-autoupdate --url http://localhost:8090 run spring-suite-test
```

Inspect or control it through the console:

```text
tunnel status
tunnel logs 100
tunnel restart
```

Set `suite.cloudflared.enabled=false` only for an intentionally local-only runtime.

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

## SQL request journal

The `suite-database` module adds persistent SQL storage, Flyway migrations and a global HTTP request journal covering every inbound HTTP route by default. The default profile uses an embedded H2 file database at `data/spring-suite`; the runtime data directory is intentionally ignored by Git.

Captured data includes request/correlation identifiers, timestamps, method and URI, query string, origin and remote metadata, headers, request body, response status, response headers/body, byte counts, truncation flags, duration and exception information. Credential-shaped fields are redacted before persistence by default, including authorization headers, cookies, passwords, API keys and tokens. Request and response body capture is bounded to 1 MiB per direction by default.

Administration endpoints:

- `GET /api/admin/requests` — paginated search. Supported parameters: `query`, `path`, `method`, `status` (`200` or `2xx`), `from`, `to`, `page`, and `size`.
- `GET /api/admin/requests/{id}` — complete stored request/response record.
- `GET /api/admin/requests/stats` — total, last-24-hour, status-class and latency statistics.
- `GET /api/admin/requests/stream` — SSE stream emitted after every successfully persisted incoming request.
- `GET /actuator/health` — includes the `suiteDatabase` health component.

The browser control panel exposes the same data in the **SQL Request Journal** section with full-text search, filters, paging and record inspection. The **Enable notifications** action requests the browser permission once, stores the local preference and subscribes to the SSE stream. After permission is granted, every newly persisted incoming request produces a native Browser Notification; clicking it focuses the panel and opens that request record. The panel page must remain open, while it may be minimized or in a background tab. Journal administration endpoints and the operator SSE stream are excluded from capture to prevent recursive/self-generated traffic.

Database and capture settings live in `config/suite-database.yml`. To use PostgreSQL, replace the datasource block, for example:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/spring_suite
    driver-class-name: org.postgresql.Driver
    username: spring_suite
    password: ${SPRING_SUITE_DB_PASSWORD}
```

The PostgreSQL JDBC and Flyway database modules are already present on the runtime classpath.

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
- `ai models [provider]`
- `ai ask [--provider id] [--model id] <prompt>`
- `ai setup <provider>`

Built-in providers:

- `openai` — module-backed adapter over `suite-openai` and the OpenAI Responses API.
- `ollama` — local OpenAI-compatible provider at `http://127.0.0.1:11434/v1`, with active model discovery and default-model validation.
- `zai` — configurable OpenAI Chat Completions compatible adapter for GLM, defaulting to `glm-5.2` and `ZAI_API_KEY`.
- `local-openai-compatible` — disabled template for other local/self-hosted OpenAI-compatible endpoints such as vLLM, SGLang or LM Studio.

Complete Ollama installation, configuration, API, security, performance and troubleshooting documentation is available in [`docs/integrations/ollama.md`](docs/integrations/ollama.md).

The older `/api/openai/*` endpoints and `openai` console command remain available as compatibility surfaces.

## Desktop helper AI suite

SpringSuite now includes `suite-desktop-helper`, a local-first assistant layer for desktop context, form hints and operator-reviewed form filling. It is deliberately assistive by default: it can read structured context, generate hints and produce a fill plan, but desktop write actions remain disabled unless explicitly enabled and approved.

Desktop helper surfaces:

- `active-window` — active app, title, URL and focused-control context through `suite-desktop-capture` or compatible sidecar.
- `screen-text` — visible text / OCR / selected text as read-only context.
- `browser-form` — structured form fields from a browser extension, accessibility bridge or DOM adapter.
- `clipboard` — disabled by default; can be enabled separately for read/write clipboard workflows.
- `keyboard-mouse` — disabled by default; reserved for future approved typing, hotkeys and pointer execution.

Runtime endpoints:

- `GET /api/desktop-helper/status` — module status, enabled surfaces, policy flags and capture-tool availability.
- `GET /api/desktop-helper/schema` — integration schema for sidecars, browser extensions and desktop drivers.
- `POST /api/desktop-helper/context/capture` — run the configured desktop capture sidecar through toolbelt and store the latest snapshot.
- `POST /api/desktop-helper/context/ingest` — ingest raw sidecar/browser/accessibility JSON and normalize it into `DesktopFocusContext`.
- `GET /api/desktop-helper/context/latest` — return the last captured or ingested snapshot, even when stale.
- `GET /api/desktop-helper/context/current` — return the latest snapshot only when its TTL is still valid.
- `DELETE /api/desktop-helper/context/latest` — clear the in-memory desktop snapshot cache.
- `POST /api/desktop-helper/context/analyze` — analyze `DesktopFocusContext` and report risk, field counts and next actions.
- `POST /api/desktop-helper/hints` — generate validation, safety and focused-field hints.
- `POST /api/desktop-helper/form-fill/plan` — map safe profile/constraint values to detected form fields and return an approval-aware plan.
- `POST /api/desktop-helper/browser-dom/snapshot` — ingest a privacy-preserving semantic snapshot of HTML `<form>` elements.
- `GET /api/desktop-helper/browser-dom/status` — inspect browser bridge connectivity and recognition counters.

### Browser `<form>` recognition

The Chromium Manifest V3 extension at `browser-extension/springsuite-form-bridge` recognizes native forms, labels, input types, required/disabled/read-only state, select options, submit controls and approximate bounds. It sends only structure plus `valuePresent`; entered values, passwords, selected-option indexes, cookies and page source are not transported. Query strings and URL fragments are removed server-side.

The ingest surface is direct-loopback-only and requires `X-SpringSuite-Browser-Token` by default, so it cannot be reached through the public cloudflared route. DOM write and submit operations remain disabled; the resulting snapshot is used for analysis, hints and fill planning. Setup and verification are documented in [`docs/integrations/browser-form-bridge.md`](docs/integrations/browser-form-bridge.md).

Console commands:

- `desktop-helper status`
- `desktop-helper schema`
- `desktop-helper surfaces`
- `desktop-helper endpoints`

Default policy:

```yaml
suite:
  desktop-helper:
    enabled: true
    mode: assistive
    require-approval-for-write-actions: true
    allow-desktop-capture: true
    allow-clipboard-read: false
    allow-clipboard-write: false
    allow-form-fill-planning: true
    allow-autofill-execution: false
    allow-submit-actions: false
    approval-token-ttl-seconds: 120
    max-approval-token-ttl-seconds: 900
```

Example form-fill planning request:

```json
{
  "userGoal": "Help fill this contact form safely.",
  "locale": "en-US",
  "profile": {
    "fullName": "Example User",
    "email": "user@example.com",
    "company": "Example Labs"
  },
  "context": {
    "platform": "windows",
    "activeApplication": "chrome.exe",
    "activeWindowTitle": "Contact form",
    "url": "https://example.test/contact",
    "form": {
      "id": "contact",
      "name": "Contact",
      "fields": [
        { "id": "name", "label": "Full name", "type": "text", "required": true },
        { "id": "email", "label": "Email", "type": "email", "required": true },
        { "id": "company", "label": "Company", "type": "text" },
        { "id": "password", "label": "Password", "type": "password", "required": true }
      ]
    }
  }
}
```

The response returns field-level actions such as `fill`, `select`, `check`, `ask`, `review` or `leave`. Sensitive fields are review-only by default and raw sensitive values are not returned in generated plans.

## Desktop bridge v1

Desktop bridge v1 closes the loop between external desktop capture tools and the AI helper. It accepts both raw sidecar output and precise browser/accessibility payloads, then normalizes them into the canonical `DesktopFocusContext` used by analyze/hints/form-fill planning.

Bridge flow:

```text
suite-desktop-capture / browser bridge / accessibility bridge
        ↓
raw desktop JSON
        ↓
DesktopContextNormalizer
        ↓
DesktopFocusContext
        ↓
DesktopSnapshotCache
        ↓
analyze / hints / form-fill-plan
```

Capture through the configured sidecar:

```powershell
Invoke-RestMethod -Method Post http://localhost:8090/api/desktop-helper/context/capture `
  -ContentType "application/json" `
  -Body '{ "args": ["capture"], "store": true }'
```

Ingest a browser/accessibility snapshot directly:

```json
{
  "source": "browser-extension",
  "store": true,
  "snapshot": {
    "platform": "windows",
    "activeWindow": {
      "process": "chrome.exe",
      "title": "Contact form",
      "url": "https://example.test/contact"
    },
    "focusedElement": {
      "role": "textbox",
      "name": "Email",
      "automationId": "email"
    },
    "screenText": {
      "selectedText": "",
      "visibleText": "Full name Email Company Message Submit"
    },
    "form": {
      "id": "contact",
      "name": "Contact",
      "fields": [
        { "id": "name", "label": "Full name", "name": "name", "type": "text", "required": true },
        { "id": "email", "label": "Email", "name": "email", "type": "email", "required": true, "focused": true },
        { "id": "password", "label": "Password", "name": "password", "type": "password", "required": true, "valuePresent": true }
      ]
    }
  }
}
```

The normalizer redacts sensitive field values during ingestion. For sensitive fields, bridges should send `valuePresent`, `valueLength` or non-secret metadata instead of raw values.

## Desktop approval layer v1

The approval layer is the safety gateway between form-fill planning and any future real desktop executor. It does not type, click, paste or submit. It issues short-lived approval tokens and performs dry-run validation against the latest fresh snapshot.

Approval flow:

```text
DesktopFormFillPlan / explicit DesktopApprovedAction[]
        в†“
POST /api/desktop-helper/approvals
        в†“
DesktopApprovalToken bound to snapshotId + action list + TTL
        в†“
POST /api/desktop-helper/actions/dry-run
        в†“
DesktopActionDryRunResult with guard status and execution preview
```

Issue an approval token from explicit actions:

```json
{
  "snapshotId": "snapshot-id-from-context-current",
  "purpose": "review contact-form fill",
  "operator": "local-operator",
  "scopes": ["desktop.actions.dry-run"],
  "ttlSeconds": 120,
  "actions": [
    {
      "actionId": "fill:email",
      "action": "fill",
      "targetFieldId": "email",
      "label": "Email",
      "value": "user@example.com",
      "write": true,
      "sensitive": false,
      "submit": false,
      "reason": "Candidate value came from reviewed profile data."
    }
  ]
}
```

Dry-run the token:

```json
{
  "approvalToken": "token-id-from-approval-response",
  "snapshotId": "snapshot-id-from-context-current",
  "markTokenUsed": false
}
```

Dry-run guards check:

- token exists, is not expired and is not used;
- token includes `desktop.actions.dry-run` or `desktop.actions.execute` scope;
- current snapshot is fresh and matches the requested/token snapshot id;
- target form fields still exist in the current snapshot;
- sensitive actions were explicitly allowed and contain no hidden automatic secret write;
- submit actions are blocked by default.

A successful dry-run records a short-lived pass tied to token id, snapshot id and action signature. The execution stub requires that pass before it can consume the token.

## Desktop execution stub v1

The execution stub is the final safety layer before any real keyboard, mouse or clipboard bridge. It still performs no real desktop input. It validates the token and prior dry-run pass, verifies the current snapshot, marks the token as used, writes an audit entry and returns a simulated execution result.

Execution-stub flow:

```text
POST /api/desktop-helper/actions/dry-run
        в†“
DryRunPass recorded for tokenId + snapshotId + action signature
        в†“
POST /api/desktop-helper/actions/execute
        в†“
validate desktop.actions.execute scope
validate token not expired/used
validate fresh current snapshot
validate prior dry-run pass
mark token used
return simulated DesktopActionExecutionResult
```

Approval token for execution must include `desktop.actions.execute`:

```json
{
  "snapshotId": "snapshot-id-from-context-current",
  "purpose": "simulate approved contact-form fill",
  "operator": "local-operator",
  "scopes": ["desktop.actions.dry-run", "desktop.actions.execute"],
  "ttlSeconds": 120,
  "actions": [
    {
      "actionId": "fill:email",
      "action": "fill",
      "targetFieldId": "email",
      "label": "Email",
      "value": "user@example.com",
      "write": true,
      "sensitive": false,
      "submit": false
    }
  ]
}
```

Execute after successful dry-run:

```json
{
  "approvalToken": "token-id-from-approval-response",
  "snapshotId": "snapshot-id-from-context-current",
  "markTokenUsed": true
}
```

The response has `simulated=true` and `executed=false`. Real typing, clicking, pasting and submitting remain reserved for a future executor behind the same guard chain.

## Full desktop integration v1

Full desktop integration v1 introduces the real executor abstraction without enabling real desktop input. The public execution endpoint remains the same, but execution now routes through explicit backend contracts:

```text
POST /api/desktop-helper/actions/execute
        в†“
DesktopApprovalService
        в†“
ExecutionGuardService
        в†“
DesktopActionExecutor
        в†“
NoopDesktopActionExecutor
        в†“
ExecutionAuditService
```

Current backend:

```text
NoopDesktopActionExecutor
- realInputEnabled=false
- supports fill/type/paste/select/check/uncheck/click/hotkey/submit as simulated actions
- returns DesktopExecutionStep records
- performs no keyboard, mouse, clipboard, DOM or UI Automation operations
```

Executor interface:

```text
DesktopActionExecutor
- descriptor()
- execute(ExecutionContext)
```

Guard layer:

```text
ExecutionGuardService
- validates executor availability
- blocks real-input executors in v1
- verifies desktop.actions.execute scope
- verifies token state
- verifies fresh snapshot
- verifies prior dry-run pass
- verifies all dry-run steps were allowed
```

Audit layer:

```text
ExecutionAuditService
- records guard failures
- records execution requests
- records execution results
- logs executor id, snapshot id, token id, simulated/executed state and step count
```

Future real backends should implement `DesktopActionExecutor` behind the same guard chain:

```text
ClipboardExecutor
KeyboardExecutor
MouseExecutor
BrowserDomExecutor
WindowsUiAutomationExecutor
```

Real input remains intentionally unimplemented. A future backend must pass through the same approval token, fresh snapshot, dry-run pass, execution guard and audit path before it can perform any write action.

## Desktop executor registry v1

The executor registry exposes every desktop action backend as metadata before any real integration is enabled.

Registry endpoints:

```text
GET /api/desktop-helper/executors
GET /api/desktop-helper/executors/{id}
```

Current executor map:

```text
noop-desktop-action-executor
- enabled=true
- realInputEnabled=false
- current default backend
- simulates execution steps only

clipboard-desktop-action-executor
- enabled=false
- realInputEnabled=false
- future ClipboardExecutor skeleton

keyboard-desktop-action-executor
- enabled=false
- realInputEnabled=false
- future KeyboardExecutor skeleton

mouse-desktop-action-executor
- enabled=false
- realInputEnabled=false
- future MouseExecutor skeleton

browser-dom-desktop-action-executor
- enabled=false
- realInputEnabled=false
- future BrowserDomExecutor skeleton

windows-ui-automation-desktop-action-executor
- enabled=false
- realInputEnabled=false
- future WindowsUiAutomationExecutor skeleton
```

`DesktopActionExecutorRegistry` is now the injection point for execution. `DesktopApprovalService` asks the registry for the default executor instead of depending on a single executor bean, so adding future backends will not break Spring injection.

Disabled skeleton backends implement `DesktopActionExecutor`, but they only expose descriptors and return `executor_disabled` if called. `ExecutionGuardService` also rejects disabled backends before invocation.

## Desktop executor selection policy v1

Executor selection is now configurable without enabling real desktop input.

Policy endpoint:

```text
GET /api/desktop-helper/executors/policy
```

Configuration:

```yaml
suite:
  desktop-helper:
    executor:
      default-id: noop-desktop-action-executor
      allowed-real-input: false
    executors:
      noop-desktop-action-executor:
        enabled: true
      clipboard-desktop-action-executor:
        enabled: false
      keyboard-desktop-action-executor:
        enabled: false
      mouse-desktop-action-executor:
        enabled: false
      browser-dom-desktop-action-executor:
        enabled: false
      windows-ui-automation-desktop-action-executor:
        enabled: false
```

Selection behavior:

```text
DesktopActionExecutorRegistry
        в†“
DesktopExecutionPolicy
        в†“
static executor descriptor + config override
        в†“
effective descriptor
```

The registry returns effective descriptors from `/api/desktop-helper/executors`, so UI and control-plane clients see configured state rather than only hardcoded backend metadata.

Real desktop input remains globally blocked by default:

```yaml
suite:
  desktop-helper:
    executor:
      allowed-real-input: false
```

When `allowed-real-input=false`, an executor with `realInputEnabled=true` is still reported as a real-input-capable backend and is blocked by `ExecutionGuardService`. Skeleton backends can be enabled for selection/testing metadata, but they do not perform real input and may still return `executor_disabled` until a real backend implementation replaces the skeleton.

## Real desktop input v1

SpringSuite now contains real-input bridge adapters for local desktop automation. Real input is not enabled by default; it requires explicit configuration plus the existing approval/dry-run/snapshot guard chain.

Real-capable components:

```text
RealDesktopActionExecutor
ClipboardBridgeAdapter
KeyboardBridgeAdapter
MouseBridgeAdapter
```

Metadata/skeleton bridge components:

```text
BrowserDomBridgeAdapter
WindowsUiAutomationBridgeAdapter
```

Bridge endpoints:

```text
GET /api/desktop-helper/bridges
GET /api/desktop-helper/bridges/{id}
GET /api/desktop-helper/bridges/policy
```

Minimal real-input config for local testing:

```yaml
suite:
  desktop-helper:
    allow-autofill-execution: true
    executor:
      default-id: real-desktop-action-executor
      allowed-real-input: true
    executors:
      real-desktop-action-executor:
        enabled: true
    bridge:
      allowed-real-input: true
    bridges:
      clipboard-bridge-adapter:
        enabled: true
      keyboard-bridge-adapter:
        enabled: true
      mouse-bridge-adapter:
        enabled: true
```

Real input remains guarded by:

```text
fresh DesktopSnapshot
approval token
prior dry-run pass
desktop.actions.execute scope
executor.allowed-real-input=true
selected executor enabled=true
bridge.allowed-real-input=true
selected bridge enabled=true
ExecutionAuditService operator log
```

Action routing:

```text
fill / paste  -> ClipboardBridgeAdapter     -> writes clipboard and sends Ctrl+V
type          -> KeyboardBridgeAdapter      -> sends key events
hotkey        -> KeyboardBridgeAdapter      -> sends key chord such as CTRL+S
submit        -> KeyboardBridgeAdapter      -> sends Enter after submit guards pass
click         -> MouseBridgeAdapter         -> moves pointer and clicks metadata x/y
```

Mouse click action metadata must include coordinates:

```json
{
  "actionId": "click:submit",
  "action": "click",
  "targetFieldId": "submit",
  "label": "Submit",
  "write": true,
  "metadata": {
    "x": 960,
    "y": 720,
    "button": "left"
  }
}
```

Hotkey action example:

```json
{
  "actionId": "hotkey:save",
  "action": "hotkey",
  "label": "Save",
  "value": "CTRL+S",
  "write": true
}
```

The browser DOM and Windows UI Automation bridges are intentionally metadata-only until their external extension/sidecar implementations are wired. They expose the contract but do not perform input.

## Real input self-test harness

The self-test endpoint validates local desktop input readiness without performing input by default.

Endpoint:

```text
POST /api/desktop-helper/real-input/self-test
```

Diagnostics-only request:

```json
{
  "perform": false
}
```

Checks performed without input:

```text
AWT headless state
system clipboard availability
AWT Robot creation
executor and bridge policy state
enabled bridge adapters
focused-window warning
```

Controlled perform request:

```json
{
  "perform": true,
  "testClipboardPaste": true,
  "testTyping": true,
  "testClick": true,
  "testText": "SpringSuite real input self-test"
}
```

When `perform=true`, SpringSuite opens a temporary Swing window titled `SpringSuite Real Input Self-Test` and targets paste/type/click actions at that window. This avoids sending test input into an arbitrary focused application. The self-test attempts to restore the previous string clipboard content after the test.

`perform=true` requires real-input policy to be enabled:

```yaml
suite:
  desktop-helper:
    allow-autofill-execution: true
    executor:
      default-id: real-desktop-action-executor
      allowed-real-input: true
    executors:
      real-desktop-action-executor:
        enabled: true
    bridge:
      allowed-real-input: true
    bridges:
      clipboard-bridge-adapter:
        enabled: true
      keyboard-bridge-adapter:
        enabled: true
      mouse-bridge-adapter:
        enabled: true
```

The self-test is intended for local operator-controlled environments only. In a headless environment it reports `desktop_headless` / failed AWT checks and does not attempt input.
