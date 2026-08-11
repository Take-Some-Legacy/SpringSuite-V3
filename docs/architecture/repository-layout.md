# Repository layout

SpringSuite is a Java 17 multi-project Gradle repository with a layered physical source tree. Gradle project IDs remain stable (`:suite-core`, `:suite-app`, and so on), while `settings.gradle.kts` maps those IDs to architecture-oriented directories under `components/`.

This separates logical module identity from filesystem layout: dependencies continue to use stable project paths, and source directories can be reorganized without rewriting every `project(":...")` dependency.

## Top-level structure

```text
SpringSuite/
├─ components/
│  ├─ foundation/                 Lowest-level runtime primitives
│  │  ├─ suite-core/
│  │  ├─ suite-platform/
│  │  └─ suite-observability/
│  ├─ contracts/                  Stable cross-subsystem API contracts
│  │  ├─ suite-ai-api/
│  │  └─ suite-desktop-api/
│  ├─ infrastructure/             Configuration, logging and persistence
│  │  ├─ suite-config/
│  │  ├─ suite-logging/
│  │  └─ suite-database/
│  ├─ runtime/                    Runtime capabilities and process/tool access
│  │  ├─ suite-command/
│  │  ├─ suite-toolbelt/
│  │  ├─ suite-workspace/
│  │  ├─ suite-module/
│  │  └─ suite-cloudflared/
│  ├─ intelligence/               AI, form intelligence and browser DOM
│  │  ├─ suite-ai/
│  │  ├─ suite-openai/
│  │  ├─ suite-form-intelligence/
│  │  └─ suite-browser-dom/
│  ├─ desktop/                    Desktop configuration and orchestration
│  │  ├─ suite-desktop-config/
│  │  └─ suite-desktop-helper/
│  ├─ application/                External agent bridge and composition root
│  │  ├─ suite-agent/
│  │  └─ suite-app/
│  └─ extensions/                 Deployable/optional feature modules
│     ├─ suite-cloudflared-module/
│     ├─ suite-diagnostics-module/
│     ├─ suite-dashboard-module/
│     └─ suite-fn-module/
├─ native/
│  └─ go/                         Native Go sidecar source trees
├─ suiteBinaries/                 Production native sidecar binaries
├─ gradle/                        Shared Gradle conventions
├─ tools/                         Toolbelt descriptors and third-party tools
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
foundation + contracts
        ↓
infrastructure
        ↓
runtime capabilities
        ↓
intelligence / desktop
        ↓
application
        ↓
composition root and deployable extensions
```

Lower-level modules must not depend on `suite-app`. Integration modules must depend on the narrowest contract module available. AI consumers use `suite-ai-api`; executable discovery uses `suite-platform`; generic runtime API, operator mode and status remain in `suite-core`.

`verifyModuleBoundaries` resolves each module through Gradle rather than assuming a root-level directory, so boundary verification remains valid after physical layout changes.

## Gradle project mapping

`settings.gradle.kts` is the source of truth for module placement. Each module is included with its stable Gradle ID and then mapped to its physical directory:

```kotlin
include(":suite-core")
project(":suite-core").projectDir = file("components/foundation/suite-core")
```

The repository verifier reads the module declarations from `settings.gradle.kts` and checks that every declared module has exactly one matching component directory with a `build.gradle.kts`.

## Shared runtime-module convention

Signed runtime modules no longer duplicate keystore generation, JAR manifest setup, signing and deployment tasks. The shared convention lives at:

```text
gradle/runtime-module-signing.gradle.kts
```

The root build defines the signed module metadata once and applies the convention to diagnostics, dashboard and FN modules. A single development signing keystore is generated under `build/module-signing/`.

## Versioning

The release version is defined once in `gradle.properties` as `suiteVersion`. Java projects inherit it from the root build. Native Windows resource metadata uses the four-component equivalent (`<suiteVersion>.0`).

`verifyVersionConsistency` checks the tracked Go `winres.json` files and `windows-resources-index.json` against the Java release version to prevent version drift.

## Source and runtime separation

The repository contains source code and reproducible production assets. Local runtime state is intentionally excluded from version control:

- `config/` except tracked portable defaults;
- `data/`;
- `logs/`;
- runtime `modules/`;
- `.springsuite/`;
- generated repository descriptors.

The deploy image is assembled under `build/deploy/`. Deployment copies application artifacts while preserving existing runtime configuration and state by default.

## Repository invariants

A valid repository must satisfy the following conditions:

1. Every SpringSuite project declared in `settings.gradle.kts` has exactly one module directory under `components/` and a `build.gradle.kts`.
2. Gradle project IDs remain stable regardless of physical source grouping.
3. The Gradle wrapper is the only required Gradle installation path.
4. Build output, IDE metadata, runtime state and debug binaries are not committed.
5. `assembleDeploy` produces a self-contained runtime image with launchers and operational scripts.
6. Signed runtime modules use the shared signing convention and one root build keystore.
7. Java and native Windows resource versions remain synchronized.
8. Existing deployment configuration, database state, logs and credentials are not overwritten unless explicitly requested.

Run `scripts/verify-repository.ps1` for repository invariants and `gradlew verifyModuleBoundaries verifyVersionConsistency` for Gradle-level architectural checks.
