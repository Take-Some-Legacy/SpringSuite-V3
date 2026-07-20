# Ollama integration for SpringSuite

This document is the complete operator and developer guide for the SpringSuite Ollama provider.

SpringSuite connects to Ollama through the provider-agnostic `suite-ai` facade and Ollama's OpenAI-compatible HTTP API. The integration intentionally uses the existing `OpenAiCompatibleChatProvider`; it does not introduce an Ollama-specific Java SDK or bypass the Suite AI contracts.

---

## 1. Scope

The integration provides:

- a built-in `ollama` provider;
- local chat completion requests through `POST /v1/chat/completions`;
- provider health checks through `GET /v1/models`;
- local model discovery;
- default-model validation;
- model selection per request;
- multi-message chat input;
- temperature, top-p and output-token controls;
- reasoning-effort forwarding for compatible thinking models;
- JSON mode through OpenAI-compatible `response_format` vendor options;
- function-tool definitions and tool-call parsing for programmatic `AiService` callers;
- console and HTTP access through existing SpringSuite AI surfaces;
- short-lived health-probe caching to avoid probing Ollama on every status read.

The integration does **not** install Ollama, download models, manage the Ollama process, expose Ollama to the public network, or execute model-requested tools automatically.

---

## 2. Architecture

```text
Console / REST / Java module
          |
          v
       AiService
          |
          v
  DefaultAiService router
          |
          v
  AiProviderRegistry
          |
          v
OpenAiCompatibleChatProvider (provider id: ollama)
          |
          +---- GET  http://127.0.0.1:11434/v1/models
          |          health probe + model discovery
          |
          +---- POST http://127.0.0.1:11434/v1/chat/completions
                     chat inference
          |
          v
        Ollama
          |
          v
   locally installed model
```

### Component ownership

| Component | Responsibility |
|---|---|
| `suite-core` | Stable provider-neutral records and interfaces: `AiService`, `AiProvider`, `AiChatRequest`, `AiChatResponse`, messages, tools, usage and capabilities. |
| `suite-ai` | Routing, provider registration, configuration binding, OpenAI-compatible transport, console commands and REST endpoints. |
| `suite-openai` | Separate module-backed OpenAI Responses API provider. It is not used for Ollama. |
| Ollama | Local model storage, model loading, inference and the OpenAI-compatible server. |

### Why the OpenAI-compatible endpoint is used

Ollama exposes both native `/api/*` endpoints and OpenAI-compatible `/v1/*` endpoints. SpringSuite uses `/v1/chat/completions` because the existing provider-neutral transport already understands that request and response shape. This keeps provider routing uniform and avoids a second Ollama-only request model.

---

## 3. Current capability matrix

The table separates Ollama server capabilities from the capabilities currently exposed by SpringSuite.

| Capability | Ollama server | SpringSuite Ollama provider | Notes |
|---|---:|---:|---|
| Text chat completions | Yes | Yes | Primary supported path. |
| Multi-message conversation input | Yes | Yes | Send system, user, assistant and tool-role messages. |
| Per-request model override | Yes | Yes | Use `--model`, `model` in REST, or `AiChatRequest.model()`. |
| Model discovery | Yes | Yes | SpringSuite probes `/v1/models`. |
| Default-model validation | N/A | Yes | `require-default-model: true` rejects a reachable server without the configured model. |
| Usage accounting | Yes | Yes | Parsed from `prompt_tokens`, `completion_tokens` and `total_tokens`. |
| JSON mode | Yes | Yes | Use `options.vendorOptions.response_format`. |
| Reasoning effort | Model-dependent | Yes | Forwarded as `reasoning_effort`; use `low`, `medium`, `high` or `none` when supported. |
| Tool definition submission | Yes | Programmatic only | `AiChatRequest.tools()` is serialized by the provider. The current REST mapper and console `ai ask` command do not accept tool schemas. |
| Tool-call parsing | Yes | Yes | Returned as `AiChatResponse.toolCalls()`. |
| Complete automatic tool loop | Yes, when implemented by caller | No | SpringSuite does not execute tools automatically, and the current message contract does not serialize assistant `tool_calls` into a follow-up turn. |
| Streaming | Yes | No | The current provider buffers and parses one JSON document. Keep `stream=false`. |
| Vision / image message parts | Yes | No | Current `AiMessage.content` is text-only. |
| Embeddings | Yes | No | No embedding contract or endpoint is implemented in `suite-ai`. |
| Native Ollama `/api/chat` | Yes | No | Deliberately not used. |
| Pull/create/delete models | Yes | No | Use the Ollama CLI or native API outside SpringSuite. |
| Automatic process startup | Platform-dependent | No | Ollama must already be running. |

---

## 4. Prerequisites

### SpringSuite

- Java 17 or newer matching the project toolchain.
- A working SpringSuite checkout.
- `suite-ai` included in `settings.gradle.kts` and on the `suite-app` runtime classpath.

### Ollama

- Ollama installed on the same machine or reachable over a trusted network.
- At least one pulled model.
- Enough storage, RAM and, preferably, GPU memory for the selected model.

On Windows, Ollama normally runs as a background application and serves its API at:

```text
http://localhost:11434
```

SpringSuite's default provider base URL includes the OpenAI-compatible prefix:

```text
http://127.0.0.1:11434/v1
```

`127.0.0.1` is used deliberately to avoid hostname-resolution ambiguity and to keep the default connection local.

---

## 5. Installation and first model

Ollama is an external runtime and is not bundled into the SpringSuite distribution.

### 5.1 Install Ollama

Install Ollama using the official platform installer. On Windows, the installer normally adds `ollama` to the user `PATH` and starts the background application.

Verify the CLI:

```powershell
ollama --version
```

Verify the server:

```powershell
Invoke-RestMethod http://127.0.0.1:11434/api/version
```

### 5.2 Pull a model

The SpringSuite default is `llama3.2`:

```powershell
ollama pull llama3.2
```

List installed models:

```powershell
ollama list
```

Verify the OpenAI-compatible model endpoint:

```powershell
Invoke-RestMethod http://127.0.0.1:11434/v1/models
```

### 5.3 Test Ollama directly before testing SpringSuite

```powershell
$body = @{
    model = "llama3.2"
    messages = @(
        @{
            role = "user"
            content = "Reply with exactly: OLLAMA_READY"
        }
    )
    stream = $false
} | ConvertTo-Json -Depth 8

Invoke-RestMethod `
    -Method Post `
    -Uri http://127.0.0.1:11434/v1/chat/completions `
    -ContentType "application/json" `
    -Body $body
```

Do not debug SpringSuite until this direct request succeeds.

---

## 6. Default SpringSuite configuration

The built-in configuration is located at:

```text
suite-ai/src/main/resources/suite-ai-default.yml
```

Relevant section:

```yaml
suite:
  ai:
    enabled: true
    default-provider: openai
    providers:
      ollama:
        enabled: true
        type: openai-chat-compatible
        name: Ollama Local
        vendor: Ollama
        base-url: ${OLLAMA_BASE_URL:http://127.0.0.1:11434/v1}
        chat-endpoint: /chat/completions
        api-key-env: ""
        requires-auth: false
        default-model: ${OLLAMA_MODEL:llama3.2}
        default-max-tokens: 4096
        default-temperature: 0.7
        request-timeout: 120s
        probe:
          enabled: true
          endpoint: /models
          timeout: 3s
          cache-ttl: 5s
          require-default-model: true
        capabilities:
          - chat
          - chat-completions-api
          - tools
          - json-mode
          - reasoning-effort
```

### Configuration bootstrap behavior

At startup, `AiConfigContributor` registers `suite-ai.yml`. `ExternalSuiteConfigBootstrap` creates the external file when it is missing and supplements missing keys when it already exists.

Runtime file:

```text
<runtime-root>/config/suite-ai.yml
```

When a new default key is added to an existing configuration, the bootstrap process:

1. parses the existing and default YAML trees;
2. merges only missing keys;
3. creates a timestamped backup beside the original file;
4. writes the supplemented external configuration;
5. preserves existing operator values.

A typical backup name is:

```text
suite-ai.yml.bak-20260720-213000
```

### Configuration precedence

Values in the external runtime configuration remain authoritative. Environment placeholders inside the YAML are resolved by Spring.

---

## 7. Configuration reference

### Provider properties

| Property | Default | Meaning |
|---|---|---|
| `suite.ai.enabled` | `true` | Master switch for the provider-neutral AI service. |
| `suite.ai.default-provider` | `openai` | Provider used when a request does not specify `providerId`. |
| `providers.ollama.enabled` | `true` | Registers the Ollama provider as enabled. It does not make it the default. |
| `providers.ollama.type` | `openai-chat-compatible` | Selects `OpenAiCompatibleChatProvider`. |
| `providers.ollama.name` | `Ollama Local` | Human-readable descriptor name. |
| `providers.ollama.vendor` | `Ollama` | Vendor label returned by provider discovery. |
| `providers.ollama.base-url` | `http://127.0.0.1:11434/v1` | Base URI including `/v1`. |
| `providers.ollama.chat-endpoint` | `/chat/completions` | Path appended to `base-url`. |
| `providers.ollama.api-key-env` | empty | Environment variable containing a Bearer token when a reverse proxy requires one. |
| `providers.ollama.requires-auth` | `false` | Whether a missing API key makes the provider unavailable. |
| `providers.ollama.default-model` | `llama3.2` | Used when a request does not name a model. |
| `providers.ollama.default-max-tokens` | `4096` | Sent as `max_tokens` unless overridden. |
| `providers.ollama.default-temperature` | `0.7` | Sent unless overridden. |
| `providers.ollama.default-top-p` | unset | Optional default `top_p`. |
| `providers.ollama.request-timeout` | `120s` | Connection setup and full inference request timeout. |
| `providers.ollama.vendor-options` | empty map | Extra request fields merged into every chat payload. |

### Probe properties

| Property | Default | Meaning |
|---|---|---|
| `probe.enabled` | `true` | Performs a real HTTP readiness check from `status()`. |
| `probe.endpoint` | `/models` | Appended to the provider base URL. |
| `probe.timeout` | `3s` | Deadline for the readiness request. |
| `probe.cache-ttl` | `5s` | Reuses a probe result for this duration. |
| `probe.require-default-model` | `true` | Requires the configured default model to appear in `/v1/models`. |

### Environment variables used by SpringSuite

| Variable | Purpose | Example |
|---|---|---|
| `OLLAMA_BASE_URL` | Overrides the provider base URL. Include `/v1`. | `http://127.0.0.1:11434/v1` |
| `OLLAMA_MODEL` | Overrides the default model. | `qwen3:8b` |
| `SPRING_SUITE_CONFIG_DIR` | Moves the complete SpringSuite external configuration directory. | `D:\SpringSuite\config` |

### Ollama-owned environment variables

These variables configure the Ollama process itself, not SpringSuite:

| Variable | Purpose |
|---|---|
| `OLLAMA_MODELS` | Changes the model storage directory. |
| `OLLAMA_HOST` | Changes the address on which Ollama listens. Avoid public binding without network controls. |
| `OLLAMA_CONTEXT_LENGTH` | Changes the server/model context length where supported. |
| `OLLAMA_ORIGINS` | Adds allowed browser origins. Not required for server-to-server SpringSuite calls. |
| `HTTPS_PROXY` | Configures outbound HTTPS proxying for model downloads. |

After changing Ollama environment variables on Windows, fully exit the tray application and start it again so the process inherits the new values.

---

## 8. Selecting Ollama as the default provider

The built-in configuration registers Ollama but leaves `openai` as the Suite default. This prevents a local service outage from silently replacing an explicitly configured cloud provider.

To make Ollama the default, edit external `config/suite-ai.yml`:

```yaml
suite:
  ai:
    default-provider: ollama
```

A request can always override the default provider explicitly.

### Recommended routing policy

Use explicit provider selection for infrastructure and agent code:

```java
AiChatRequest request = AiChatRequest.prompt(
        "ollama",
        "llama3.2",
        "Summarize the current runtime state."
);
```

Rely on `default-provider` only for operator-facing convenience commands and environments where the routing decision is intentionally global.

---

## 9. Console commands

### List providers

```text
ai providers
```

Expected Ollama descriptor fragment:

```text
- ollama type=openai-chat-compatible vendor=Ollama model=llama3.2 enabled=true
```

### Show the default provider

```text
ai default
```

### Check Ollama readiness

```text
ai status ollama
```

A ready provider reports:

```text
AI status: READY
provider=ollama kind=none source=none
message=Provider reachable; 1 model(s) available
```

An installed but stopped Ollama server reports `UNAVAILABLE` and includes the connection error in the status metadata.

### List discovered models

```text
ai models ollama
```

This command reads `availableModels` from the provider's cached `/v1/models` probe.

### Show setup instructions

```text
ai setup ollama
```

### Ask with the configured default Ollama model

```text
ai ask --provider ollama Explain the Suite AI routing architecture
```

### Ask with an explicit model

```text
ai ask --provider ollama --model qwen3:8b Review this Java API design
```

### Console limitations

The console command currently sends one user message with default generation options. It does not expose:

- system messages;
- full conversation history;
- tools;
- JSON mode;
- reasoning effort;
- per-request temperature or top-p;
- streaming.

Use the REST or Java API for advanced requests.

---

## 10. SpringSuite HTTP API

SpringSuite listens on port `8090` by default.

### 10.1 List providers

```http
GET /api/ai/providers
```

PowerShell:

```powershell
Invoke-RestMethod http://localhost:8090/api/ai/providers
```

### 10.2 Read the default provider

```http
GET /api/ai/default-provider
```

### 10.3 Read Ollama status and models

```http
GET /api/ai/status?provider=ollama
```

PowerShell:

```powershell
$status = Invoke-RestMethod `
    "http://localhost:8090/api/ai/status?provider=ollama"

$status.data.metadata.availableModels
```

There is currently no separate `/api/ai/models` endpoint. Model discovery is returned in status metadata when the probe is enabled.

### 10.4 Minimal chat request

The field is named `providerId`, not `provider`.

```powershell
$body = @{
    providerId = "ollama"
    model = "llama3.2"
    input = "Reply with exactly: SUITE_OLLAMA_READY"
} | ConvertTo-Json -Depth 16

Invoke-RestMethod `
    -Method Post `
    -Uri http://localhost:8090/api/ai/chat `
    -ContentType "application/json" `
    -Body $body
```

### 10.5 Multi-message request

```json
{
  "providerId": "ollama",
  "model": "llama3.2",
  "messages": [
    {
      "role": "system",
      "content": "You are a concise Java architecture reviewer."
    },
    {
      "role": "user",
      "content": "Explain why provider routing belongs outside domain agents."
    }
  ],
  "options": {
    "maxTokens": 1200,
    "temperature": 0.2,
    "topP": 0.9,
    "stream": false
  }
}
```

### 10.6 Request fields

| Field | Type | Required | Meaning |
|---|---|---:|---|
| `providerId` | string | No | Defaults to `suite.ai.default-provider`. Use `ollama` for explicit local routing. |
| `model` | string | No | Defaults to the provider's configured model. |
| `input` | string | No | Shortcut that creates one user message when `messages` is empty. |
| `messages` | array | No | Conversation messages. |
| `options` | object | No | Generation options. |

### Message fields

| Field | Type | Meaning |
|---|---|---|
| `role` | string | `system`, `user`, `assistant` or `tool`. Unknown roles fall back to `user`. |
| `content` | string | Text message body. |
| `name` | string | Optional OpenAI-compatible message name. |
| `toolCallId` | string | Serialized as `tool_call_id` for tool-role messages. |

### Generation options

| Field | Type | Mapping |
|---|---|---|
| `maxTokens` | integer | `max_tokens` |
| `temperature` | number | `temperature` |
| `topP` | number | `top_p` |
| `stream` | boolean | `stream`; must remain `false` with the current provider implementation. |
| `reasoningEffort` | string | `reasoning_effort` |
| `thinking` | boolean | Generic provider-specific `thinking` object; do not use for Ollama. Prefer `reasoningEffort`. |
| `store` | boolean | Present in the neutral contract but not emitted by this provider. |
| `vendorOptions` | object | Merged into the outgoing JSON payload after standard fields. |

### Response shape

`SuiteApiResponse.data` contains an `AiChatResponse`:

```json
{
  "ok": true,
  "providerId": "ollama",
  "model": "llama3.2:latest",
  "responseId": "chatcmpl-...",
  "outputText": "SUITE_OLLAMA_READY",
  "toolCalls": [],
  "usage": {
    "inputTokens": 14,
    "outputTokens": 6,
    "totalTokens": 20
  },
  "errorCode": "",
  "errorMessage": "",
  "metadata": {
    "httpStatus": 200,
    "requestId": "",
    "durationMs": 481,
    "finishReason": "stop"
  }
}
```

---

## 11. JSON mode

Ollama's OpenAI-compatible endpoint accepts `response_format`. SpringSuite can forward it through `vendorOptions`.

```json
{
  "providerId": "ollama",
  "model": "llama3.2",
  "messages": [
    {
      "role": "user",
      "content": "Return a JSON object with keys name and purpose for SpringSuite."
    }
  ],
  "options": {
    "temperature": 0,
    "stream": false,
    "vendorOptions": {
      "response_format": {
        "type": "json_object"
      }
    }
  }
}
```

Recommended practices:

- explicitly tell the model to return JSON;
- use `temperature: 0` for deterministic extraction tasks;
- validate `outputText` with Jackson against the expected schema;
- treat model output as untrusted input;
- do not deserialize directly into privileged command objects without validation.

`vendorOptions` is merged last and can override standard request fields. Keep it under application control; do not pass arbitrary end-user maps directly into it.

---

## 12. Reasoning models

Ollama's OpenAI-compatible endpoint supports `reasoning_effort` for compatible models.

```json
{
  "providerId": "ollama",
  "model": "gpt-oss:20b",
  "input": "Compare two possible cache eviction strategies.",
  "options": {
    "reasoningEffort": "high",
    "temperature": 0.2,
    "stream": false
  }
}
```

Supported effort values are model-dependent. Common values are:

- `none`;
- `low`;
- `medium`;
- `high`.

The current `AiChatResponse` exposes final output text but does not have a dedicated field for a separate reasoning trace. Do not build business logic that depends on receiving chain-of-thought text.

Do not set `options.thinking=true` for Ollama. That flag currently emits a generic provider-specific object designed for other compatible vendors. Use `reasoningEffort` instead.

---

## 13. Programmatic Java API

### 13.1 Basic request

```java
import com.takesome.springsuite.core.ai.AiChatRequest;
import com.takesome.springsuite.core.ai.AiChatResponse;
import com.takesome.springsuite.core.ai.AiService;

public final class LocalReasoner {
    private final AiService aiService;

    public LocalReasoner(AiService aiService) {
        this.aiService = aiService;
    }

    public String explain(String question) {
        AiChatResponse response = aiService.chat(
                AiChatRequest.prompt("ollama", "llama3.2", question)
        );
        if (!response.ok()) {
            throw new IllegalStateException(
                    response.errorCode() + ": " + response.errorMessage()
            );
        }
        return response.outputText();
    }
}
```

### 13.2 Full generation options

```java
import com.takesome.springsuite.core.ai.AiChatRequest;
import com.takesome.springsuite.core.ai.AiGenerationOptions;
import com.takesome.springsuite.core.ai.AiMessage;
import java.util.List;
import java.util.Map;

AiGenerationOptions options = new AiGenerationOptions(
        2048,
        0.2,
        0.9,
        false,
        "medium",
        null,
        null,
        Map.of()
);

AiChatRequest request = new AiChatRequest(
        "ollama",
        "qwen3:8b",
        List.of(
                AiMessage.system("You are a strict software architecture reviewer."),
                AiMessage.user("Review the provider registry design.")
        ),
        options,
        List.of(),
        Map.of("source", "architecture-review")
);
```

### 13.3 Tools

The Java contract can submit tool definitions:

```java
import com.takesome.springsuite.core.ai.AiToolDefinition;
import java.util.List;
import java.util.Map;

AiToolDefinition readRuntimeStatus = new AiToolDefinition(
        "read_runtime_status",
        "Read the current SpringSuite runtime status.",
        Map.of(
                "type", "object",
                "properties", Map.of(),
                "additionalProperties", false
        )
);

AiChatRequest request = new AiChatRequest(
        "ollama",
        "qwen3:8b",
        List.of(AiMessage.user("Check whether the Suite is healthy.")),
        AiGenerationOptions.defaults(),
        List.of(readRuntimeStatus),
        Map.of()
);

AiChatResponse response = aiService.chat(request);
response.toolCalls().forEach(call -> {
    System.out.println(call.name() + " " + call.arguments());
});
```

Security rule: a tool call is only a model proposal. Validate the tool name and arguments against an allowlist, apply authorization and risk policy, execute the tool in the appropriate Suite executor, and record the result in the audit trail.

### Current tool-loop limitation

The provider can submit tools and parse the first model tool call, but the current neutral `AiMessage` record cannot carry an assistant message's `tool_calls` collection into the next request. A complete multi-turn agent loop therefore requires a future core-contract extension before it is considered production-ready.

---

## 14. Provider status semantics

`OpenAiCompatibleChatProvider.status()` performs these checks in order:

1. provider enabled;
2. non-empty `base-url`;
3. required credential present, when authentication is enabled;
4. active HTTP probe, when configured;
5. successful 2xx response from `/v1/models`;
6. default model present, when required.

### Probe metadata

A successful status response may contain:

```json
{
  "baseUrl": "http://127.0.0.1:11434/v1",
  "chatEndpoint": "/chat/completions",
  "defaultModel": "llama3.2",
  "probeEndpoint": "http://127.0.0.1:11434/v1/models",
  "probeCheckedAt": "2026-07-20T19:00:00Z",
  "probeHttpStatus": 200,
  "availableModels": [
    "llama3.2:latest",
    "qwen3:8b"
  ],
  "availableModelCount": 2
}
```

Model validation normalizes a trailing `:latest`, so these names match:

```text
llama3.2
llama3.2:latest
```

Other tags remain significant:

```text
qwen3:4b != qwen3:8b
```

### Probe cache

The provider stores the last probe result in memory for `cache-ttl`. This prevents frequent control-panel polling from issuing an HTTP request to Ollama on every refresh.

The cache is per provider instance and is lost on SpringSuite restart.

---

## 15. Error model

### SpringSuite-generated errors

| Code | Cause |
|---|---|
| `ai_disabled` | `suite.ai.enabled=false`. |
| `ai_provider_unavailable` | Provider status failed before inference. |
| `ai_io_error` | Network, DNS, connection or response I/O failure. |
| `ai_interrupted` | Calling thread interrupted. |
| `ai_runtime_error` | Unexpected transport or payload runtime error. |
| `ai_exception` | Router-level provider lookup or execution exception. |
| `ai_empty_prompt` | Console `ai ask` received no prompt. |
| `ai_models_failed` | Console model-discovery operation failed. |

### Ollama HTTP errors

Non-2xx responses are converted into failed `AiChatResponse` values. The provider reads OpenAI-style errors from:

```json
{
  "error": {
    "code": "...",
    "message": "..."
  }
}
```

When the response does not contain that shape, SpringSuite falls back to:

- `http_<status>` as the error code;
- the raw response JSON or `HTTP <status>` as the message.

---

## 16. Security model

### Local-only default

The default URL points to `127.0.0.1`. Keep it local unless remote inference is an explicit deployment requirement.

### Remote Ollama

When Ollama is hosted on another machine:

1. place it behind a trusted reverse proxy;
2. use TLS;
3. require authentication;
4. restrict source networks;
5. do not expose port `11434` directly to the public Internet;
6. configure SpringSuite with the proxy `/v1` URL;
7. store the proxy Bearer token in an environment variable;
8. set `requires-auth: true`.

Example:

```yaml
suite:
  ai:
    providers:
      ollama:
        base-url: https://ollama.internal.example/v1
        requires-auth: true
        api-key-env: OLLAMA_PROXY_TOKEN
```

The generic provider sends:

```http
Authorization: Bearer <value of OLLAMA_PROXY_TOKEN>
```

### Prompt and output trust

- Treat prompt content and model output as untrusted data.
- Never execute generated shell commands automatically.
- Never let a model bypass Suite command risk classification or approval checks.
- Validate structured output before use.
- Keep secrets out of prompts unless the policy explicitly permits disclosure to the selected model.
- Ollama is local by default, but local execution does not make prompt injection harmless.
- Audit provider id, model, duration, usage and errors without logging credentials.

### Audit logging

`AiAuditService` redacts credential-shaped metadata keys and Bearer values. The chat adapter logs request metadata but not the complete prompt body by default.

---

## 17. Performance and capacity planning

### Model selection

Choose a model according to:

- available VRAM and system RAM;
- required context length;
- coding/reasoning quality;
- latency target;
- tool-calling support;
- quantization;
- expected concurrency.

A model name in configuration does not download or load the model. Pull it explicitly with `ollama pull`.

### Cold starts

The first request can take substantially longer because Ollama must load the model into memory. Keep `request-timeout` large enough for cold loading.

Example:

```yaml
request-timeout: 300s
```

The health probe does not load the model; it only lists installed models. A `READY` status therefore means the server and model registration are available, not that first-token latency is already warm.

### Context length

The OpenAI-compatible request does not provide an Ollama-specific context-size field. Configure context length in Ollama, for example through an Ollama environment setting or a custom `Modelfile`:

```text
FROM llama3.2
PARAMETER num_ctx 8192
```

Create the derived model:

```powershell
ollama create llama3.2-8k -f .\Modelfile
```

Then select it in SpringSuite:

```powershell
$env:OLLAMA_MODEL = "llama3.2-8k"
```

### Output length

`default-max-tokens` limits completion output, not the complete context window. Large output limits increase latency and memory pressure.

### Probe tuning

For a local server, the defaults are intentionally aggressive:

```yaml
probe:
  timeout: 3s
  cache-ttl: 5s
```

For a remote server with higher latency, increase both values. Do not use the full inference timeout for health checks.

---

## 18. Development workflow

Run commands from the repository root.

### Compile and test only `suite-ai`

Windows:

```powershell
.\gradlew.bat :suite-ai:test
```

Unix-like shell:

```bash
./gradlew :suite-ai:test
```

### Build the application

```powershell
.\gradlew.bat :suite-app:bootJar
```

### Run from source

```powershell
.\gradlew.bat :suite-app:bootRun
```

### Verify after startup

```powershell
Invoke-RestMethod "http://localhost:8090/api/ai/status?provider=ollama"
```

```powershell
$body = @{
    providerId = "ollama"
    input = "Reply with OLLAMA_OK"
} | ConvertTo-Json -Depth 8

Invoke-RestMethod `
    -Method Post `
    -Uri http://localhost:8090/api/ai/chat `
    -ContentType "application/json" `
    -Body $body
```

---

## 19. Automated tests

The integration test class is:

```text
suite-ai/src/test/java/com/takesome/springsuite/ai/OpenAiCompatibleChatProviderTest.java
```

It uses the JDK `HttpServer` as a deterministic local Ollama-compatible stub.

Covered behavior:

- `/v1/models` probe;
- parsing discovered model ids;
- matching `llama3.2` to `llama3.2:latest`;
- no Authorization header when auth is disabled;
- chat request serialization;
- successful response parsing;
- usage parsing;
- missing-default-model status failure.

Recommended additional tests:

- connection refusal;
- probe timeout;
- non-2xx probe response;
- malformed model-list JSON;
- probe cache reuse and expiry;
- tagged model mismatch;
- tool-call response parsing;
- JSON mode forwarding;
- reasoning-effort forwarding;
- non-2xx chat errors;
- interrupted request handling.

---

## 20. Troubleshooting runbook

### `ai status ollama` reports connection refused

Checks:

```powershell
ollama --version
Invoke-RestMethod http://127.0.0.1:11434/api/version
Invoke-RestMethod http://127.0.0.1:11434/v1/models
```

Actions:

- start the Ollama desktop application;
- or run `ollama serve` in a dedicated terminal/service;
- verify no other process owns port `11434`;
- verify `OLLAMA_BASE_URL` includes `/v1`;
- check firewall or proxy rules for a remote endpoint.

### Provider is reachable but default model is not installed

Symptom:

```text
Provider is reachable, but default model 'llama3.2' is not installed
```

Actions:

```powershell
ollama list
ollama pull llama3.2
```

Or select an installed model:

```powershell
$env:OLLAMA_MODEL = "qwen3:8b"
```

Restart SpringSuite after changing environment variables.

### `ai ask` routes to OpenAI instead of Ollama

Cause: `default-provider` is still `openai` and the command did not provide `--provider`.

Use:

```text
ai ask --provider ollama <prompt>
```

Or configure:

```yaml
suite:
  ai:
    default-provider: ollama
```

### REST request says unknown provider or routes incorrectly

Use `providerId`, not `provider`:

```json
{
  "providerId": "ollama",
  "input": "Hello"
}
```

### Empty or malformed output when `stream=true`

The current SpringSuite transport expects one complete JSON document. Set:

```json
"stream": false
```

Streaming requires a future dedicated streaming contract and parser.

### Request times out during the first call

- increase `request-timeout`;
- use a smaller model;
- verify available memory and GPU allocation;
- test the same model directly with `ollama run`;
- retry after the model has loaded once.

### HTTP 404 from `/v1/models` or `/v1/chat/completions`

- verify the base URL ends in `/v1`;
- upgrade Ollama to a version with the required OpenAI-compatible endpoint;
- confirm a reverse proxy preserves the `/v1` path;
- inspect proxy rewrite rules.

### Tool call is returned but no final answer appears

This is expected for a model that chooses a tool. The response may contain `toolCalls` and an empty `outputText`. The caller must validate and execute the requested tool, then perform a follow-up request. The current neutral message contract still needs extension for a fully faithful assistant-tool-result loop.

### `reasoningEffort` has no effect

- confirm the selected model supports reasoning controls;
- use `low`, `medium`, `high` or `none`;
- do not use `thinking=true` for the Ollama provider;
- inspect the outgoing provider logs and direct Ollama behavior.

### Configuration did not appear in an existing deployment

On startup, the bootstrap should supplement missing keys and create a backup. Check:

```text
<runtime-root>/config/suite-ai.yml
<runtime-root>/config/suite-ai.yml.bak-<timestamp>
```

Ensure the new `suite-ai` JAR/resource is present in the deployment and restart the application.

### Windows log locations

Common Ollama locations:

```text
%LOCALAPPDATA%\Ollama
%LOCALAPPDATA%\Programs\Ollama
%HOMEPATH%\.ollama
```

Inspect `server.log` and `app.log` under `%LOCALAPPDATA%\Ollama` when the server does not start or GPU initialization fails.

---

## 21. Operational checklist

### Before deployment

- [ ] Ollama is installed on the target machine.
- [ ] The target model is pulled.
- [ ] `/v1/models` returns the model.
- [ ] The direct `/v1/chat/completions` smoke test succeeds.
- [ ] `OLLAMA_BASE_URL` includes `/v1`.
- [ ] `OLLAMA_MODEL` matches an installed model or its `:latest` form.
- [ ] Remote endpoints use TLS, authentication and network restrictions.
- [ ] `request-timeout` covers cold model loading.
- [ ] `stream` remains disabled.

### After SpringSuite startup

- [ ] `ai providers` lists `ollama`.
- [ ] `ai status ollama` reports `READY`.
- [ ] `ai models ollama` lists the expected model.
- [ ] `POST /api/ai/chat` succeeds with `providerId=ollama`.
- [ ] Operator logs contain provider/model/duration metadata and no secrets.
- [ ] Failure behavior is tested by temporarily stopping Ollama.

---

## 22. Extension roadmap

The current implementation is intentionally conservative. Logical next extensions are:

1. a provider-neutral streaming response contract;
2. SSE streaming from `/api/ai/chat/stream`;
3. rich content parts for image and multimodal messages;
4. a complete assistant `tool_calls` message representation;
5. an audited agent tool-execution loop;
6. embedding contracts and an Ollama embedding provider;
7. optional native Ollama administration endpoints for model metadata only;
8. readiness, liveness and warm-model health indicators;
9. per-provider concurrency limits and circuit breaking;
10. metrics for first-token latency, total latency, tokens per second and model load time.

Model pull/delete operations should remain separately authorized administrative actions rather than implicit chat-provider behavior.

---

## 23. Official references

- Ollama documentation: <https://docs.ollama.com/>
- OpenAI compatibility: <https://docs.ollama.com/api/openai-compatibility>
- Windows installation and troubleshooting: <https://docs.ollama.com/windows>
- API introduction: <https://docs.ollama.com/api/introduction>
- Model listing: <https://docs.ollama.com/api/tags>
- Tool calling: <https://docs.ollama.com/capabilities/tool-calling>
- Structured outputs: <https://docs.ollama.com/capabilities/structured-outputs>
- Thinking models: <https://docs.ollama.com/capabilities/thinking>
- Modelfile reference: <https://docs.ollama.com/modelfile>

---

## 24. Quick reference

```powershell
# Ollama
ollama pull llama3.2
ollama list
Invoke-RestMethod http://127.0.0.1:11434/v1/models

# SpringSuite from source
.\gradlew.bat :suite-ai:test
.\gradlew.bat :suite-app:bootRun

# SpringSuite console
ai providers
ai status ollama
ai models ollama
ai ask --provider ollama Hello from SpringSuite

# SpringSuite REST
$body = @{
    providerId = "ollama"
    input = "Hello from SpringSuite"
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Post `
    -Uri http://localhost:8090/api/ai/chat `
    -ContentType "application/json" `
    -Body $body
```
