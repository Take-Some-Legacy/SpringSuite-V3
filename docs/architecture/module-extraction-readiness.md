# SpringSuite module extraction readiness

## Goal

Keep the bootable suite small and stable by leaving only runtime-critical infrastructure in the system layer and moving feature surfaces into deployable SuiteModule jars.

## System layer: keep in app/runtime

These parts should stay system-owned because they define boot, configuration, command routing, module loading, auth, logging, and filesystem safety boundaries:

- `suite-core` — minimal API envelope, status model and runtime operator mode only.
- `suite-ai-api` — stable provider-neutral AI contracts with no Spring or runtime implementation dependency.
- `suite-platform` — isolated OS/executable discovery primitives.
- `suite-config` — external configuration bootstrap and config contributors.
- `suite-logging` — operator log pipeline and console/Jansi integration.
- `suite-module` — module bootstrap, trust, registry, lifecycle, publisher management.
- `suite-command` — command registry, console listener, command context, shared console progress.
- `suite-agent` — MCP/OAuth/bridge-token surface.
- `suite-workspace` — workspace path policy and local mutation API.
- `suite-toolbelt` — descriptor-driven tool discovery/execution kernel.

## Core decomposition completed

The first core-hardening phase is complete:

- `core.ai.*` moved physically from `suite-core` to `suite-ai-api` without changing Java package names or public type names.
- `PlatformExecutables` moved from `suite-core` to `suite-platform`.
- AI/runtime consumers now declare explicit dependencies instead of receiving these capabilities through `suite-core`.
- `suite-core` no longer owns provider contracts or OS-specific executable discovery.

This is a source-compatible structural extraction. Package renaming is intentionally deferred until downstream feature decomposition is complete.

## Desktop capability decomposition

The second decomposition phase is complete:

- `suite-desktop-api` now owns immutable form, snapshot, approval and execution contracts.
- `DesktopSnapshotIngestor` defines the normalization/storage input port.
- `DesktopSnapshotConsumer` defines the downstream orchestration notification port.
- `suite-browser-dom` now owns DOM snapshot ingest, browser REST endpoints, command queue, browser bridge adapter and browser executor descriptor.
- `BrowserDomService` no longer depends on `DesktopBridgeService` or `DesktopAgentService` concrete implementations.
- `suite-desktop-helper` implements the two ports through `DesktopBridgeService` and `DesktopAgentService` and composes the browser capability from above.

Current dependency DAG:

```text
suite-desktop-api
        ↑
suite-browser-dom
        ↑
suite-desktop-helper
```

The third desktop-hardening phase is complete:

- `suite-desktop-config` owns typed desktop, agent and sidecar configuration.
- `suite-form-intelligence` owns form analysis, memory matching, ChatGPT Plus relay state, optional provider-API planning and local safety filtering.
- `suite-observability` owns bounded Micrometer metrics and correlation-id primitives.
- deterministic local plans use a bounded 256-entry, 2-second Caffeine cache; AI and Plus branches bypass it.
- correlation ids propagate from snapshot ingest through plan metadata, browser command queue and acknowledgement logs.
- `verifyModuleBoundaries` enforces dependency direction and contract purity in local builds and matrix CI.

Current dependency DAG:

```text
suite-desktop-api       suite-desktop-config       suite-observability
        ↑                         ↑                         ↑
        ├──────── suite-form-intelligence ────────────────┤
        └──────── suite-browser-dom ──────────────────────┤
                              ↑
                    suite-desktop-helper
```

Remaining extraction wave:

```text
suite-desktop-api
    ↑
    ├─ suite-form-intelligence
    ├─ suite-desktop-runtime
    └─ suite-browser-dom
              ↑
       suite-desktop-agent
              ↑
     suite-desktop-helper facade
```

Remaining cycle breakers:

- replace concrete bridge/executor registry access with capability catalog ports;
- move snapshot cache, approval, execution guards and native adapters into `suite-desktop-runtime`;
- move sidecar lifecycle, scan orchestration and Swing UI into `suite-desktop-agent` / `suite-desktop-ui`;
- reduce `suite-desktop-helper` to composition, controllers and compatibility façades only.

## Already module-shaped

These are good examples for the next extraction wave:

- `suite-dashboard-module`
- `suite-diagnostics-module`

Both should remain outside the core app dependency graph and be deployed through signed module jars.

## Next extraction candidates

### 1. Cloudflared tunnel surface

Phase 1 complete:

- `suite-cloudflared` is now the low-level runtime library: service, properties and config contributor.
- `suite-cloudflared-module` owns the feature surface: REST controller and console `tunnel` command.
- `suite-command` no longer depends on `suite-cloudflared`.
- `suite-app` depends on `suite-cloudflared-module` instead of directly depending on `suite-cloudflared`.

Remaining externalization target:

- Add a web-extension SPI before trying to load the REST controller from an external signed jar.
- Then remove the direct `suite-app -> suite-cloudflared-module` dependency and deploy the module through the runtime modules directory.

### 2. Dashboard UI

Already extracted as a module. Keep improving it as the reference pattern for feature modules.

### 3. Diagnostics commands

Already extracted as a module. Use this as the reference for read-only operational commands.

### 4. Optional workspace enrichments

The workspace kernel should stay system-owned, but non-critical features can become modules later:

- advanced tree renderers
- symbolic analysis
- heavy search/indexing
- project-specific workspace reports

### 5. Optional toolbelt adapters

The descriptor-driven toolbelt kernel should stay system-owned. Vendor/tool-specific command façades can become modules.

## Dependency-pressure notes

Current strong coupling to reduce next:

- `suite-app -> suite-cloudflared-module` is now the only remaining cloudflared feature coupling
- `suite-command -> suite-cloudflared` has been removed

The desired end state is:

```text
suite-app
  -> suite-core
  -> suite-config
  -> suite-logging
  -> suite-module
  -> suite-command
  -> suite-agent
  -> suite-workspace
  -> suite-toolbelt

feature modules
  -> suite-module
  -> suite-command when they expose commands
```

## Cloudflared runtime data policy

Cloudflared must not write tunnel credentials/cache into the OS user profile by default. The runtime process is now prepared to use a local project directory:

```text
.springsuite/cloudflared
```

The child process receives local HOME-style environment variables and runs with that directory as its working directory. This keeps generated tunnel/cache data scoped to the current suite working directory.

## Console output policy

Interactive console commands must keep stdout clean and shell-like. Automatic progress rendering is disabled for regular commands; long-running commands may opt into explicit progress output only when it improves operator feedback and does not corrupt the prompt.


## Repository descriptor policy

A workspace may contain many repositories. A repository is any directory containing `.git`. The current repository is resolved from the active workspace path by walking upward until the nearest `.git` is found. Repository catalog discovery also scans configured workspace roots for nested repositories.

```text
.springsuite-repository.json
```

The descriptor stores:

- full repository path
- `.git` directory path
- dataset roots for examples and analysis
- analysis target categories
- excluded/generated paths
- build/test/deploy commands
- workspace safe roots
- module extraction hints

The workspace module owns descriptor creation through `RepositoryDescriptorService`. On startup it checks configured workspace roots and creates a missing descriptor when `suite.workspace.repository-descriptor-auto-create=true`.

Repository catalog API:

```text
GET /api/workspace/repositories?path=.
GET /api/workspace/repositories?path=some/sub/repo&ensure=true
```

Console:

```text
workspace repos [path] [--ensure] [--overwrite]
workspace repo [path] [--overwrite]
```

`workspace repos` returns all detected repositories and marks the current repository according to the path argument.

## Repository memory/cache policy

Repository descriptors and repository memory are separate:

```text
<repo>/.springsuite-repository.json        # generated local descriptor, ignored by git
<workspace>/.springsuite/repositories.json # local suite memory/cache, ignored by git
```

The cache remembers selected repositories between runs. Sources of remembered repositories:

- explicit config: `suite.workspace.repository-cache-roots`
- startup/catalog scans when `suite.workspace.repository-cache-remember-discovered=true`
- manual commands: `workspace remember-repo [path]`
- REST: `POST /api/workspace/repositories/remember?path=...`

Forgetting:

```text
workspace forget-repo [path]
POST /api/workspace/repositories/forget?path=...
```

Repository catalog combines:

- scanned repositories under workspace roots
- configured cache roots
- remembered cache entries from `.springsuite/repositories.json`

Current repository is still resolved from the active path by walking upward to the nearest `.git`.

## UNIX-like console policy

The interactive console is a managed shell, not an unrestricted OS shell. It supports a bash-like command surface while routing operations through Suite policies:

```text
pwd
cd [path]
ls [path] [limit]
tree [path] [depth] [limit]
cat <path>
grep <query> [path]
mkdir <path>
rm [-r|--recursive] [--dry-run] <path>
touch <path>
echo [text...]
```

Shell chaining is supported:

```text
command1 ; command2
command1 && command2
```

`;` continues regardless of prior result. `&&` executes the next command only if the previous command succeeded.

The shell maintains a virtual current working directory through `ConsoleShellState`. File operations remain constrained by workspace roots, deny lists, and mutation gates.
