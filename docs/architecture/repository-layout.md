# Repository layout

SpringSuite is a Java 17 multi-project Gradle repository. The repository deliberately keeps Java modules at the root because Gradle project paths map directly to directory names and are referenced by build scripts, module descriptors, deployment tasks and runtime documentation.

## Top-level structure

```text
SpringSuite/
├─ suite-core/                    Shared contracts and platform primitives
├─ suite-config/                  External configuration bootstrap
├─ suite-logging/                 Operator and runtime logging
├─ suite-database/                SQL persistence and request journal
├─ suite-module/                  Signed runtime-module infrastructure
├─ suite-command/                 Console command registry and built-ins
├─ suite-toolbelt/                External tool discovery and execution
├─ suite-workspace/               Bounded repository and filesystem access
├─ suite-ai/                      Provider-neutral AI routing
├─ suite-openai/                  OpenAI provider integration
├─ suite-desktop-helper/          Desktop and browser assistance layer
├─ suite-agent/                   MCP, OAuth and external agent bridge
├─ suite-cloudflared/             Managed Cloudflare Tunnel lifecycle
├─ suite-app/                     Spring Boot composition root
├─ suite-*-module/                Signed feature modules
├─ goSrc/                         Native Go sidecar source trees
├─ suiteBinaries/                 Production native sidecar binaries
├─ tools/                         Toolbelt descriptors
├─ browser-extension/             Browser integrations
├─ static/                        Runtime web assets
├─ scripts/                       Build, verification, cleanup and deployment
├─ docs/architecture/             Architecture and module-boundary documents
├─ docs/integrations/             External integration guides
└─ docs/operations/               Deployment and runtime operations
```

## Dependency direction

The intended dependency flow is:

```text
suite-core
   ↑
suite-config / suite-logging / suite-database
   ↑
suite-command / suite-toolbelt / suite-workspace / suite-ai
   ↑
feature integrations and signed modules
   ↑
suite-agent
   ↑
suite-app
```

Lower-level modules must not depend on `suite-app`. Integration modules should depend on contracts from `suite-core` or a narrow infrastructure module rather than reaching across unrelated feature packages.

## Source and runtime separation

The repository contains source code and reproducible production assets. Local runtime state is intentionally excluded from version control:

- `config/`
- `data/`
- `logs/`
- `modules/`
- `.springsuite/`
- `.springsuite-repository.json`

The deploy image is assembled under `build/deploy/`. Deployment copies application artifacts while preserving existing runtime configuration and state by default.

## Naming conventions

- Java modules use the `suite-<capability>` prefix.
- Signed external modules use the `suite-<capability>-module` suffix.
- Native sidecar descriptors live under `tools/` and resolve executables from `suiteBinaries/`.
- Operational PowerShell scripts use verb-based names such as `deploy.ps1`, `clean.ps1` and `verify-repository.ps1`.
- Documentation filenames use lowercase kebab-case.

## Repository invariants

A valid repository must satisfy the following conditions:

1. Every project declared in `settings.gradle.kts` has a directory and `build.gradle.kts`.
2. The Gradle wrapper is the only required Gradle installation path.
3. Build output, IDE metadata, runtime state and debug binaries are not committed.
4. `assembleDeploy` produces a self-contained runtime image with launchers and operational scripts.
5. Existing deployment configuration, database state, logs and credentials are not overwritten unless explicitly requested.
6. Cloudflared starts through the SpringSuite lifecycle after the local HTTP server is ready.

Run `scripts/verify-repository.ps1` to check these invariants.
