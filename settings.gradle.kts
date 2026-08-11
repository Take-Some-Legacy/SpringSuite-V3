pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "spring-suite"

// Keep stable Gradle project IDs while allowing the physical source tree to be grouped by architecture.
val componentGroups = linkedMapOf(
    "foundation" to listOf(
        "suite-core",
        "suite-platform",
        "suite-observability"
    ),
    "contracts" to listOf(
        "suite-ai-api",
        "suite-desktop-api"
    ),
    "infrastructure" to listOf(
        "suite-config",
        "suite-logging",
        "suite-database"
    ),
    "runtime" to listOf(
        "suite-command",
        "suite-toolbelt",
        "suite-workspace",
        "suite-module",
        "suite-cloudflared"
    ),
    "intelligence" to listOf(
        "suite-ai",
        "suite-openai",
        "suite-form-intelligence",
        "suite-browser-dom"
    ),
    "desktop" to listOf(
        "suite-desktop-config",
        "suite-desktop-helper"
    ),
    "application" to listOf(
        "suite-agent",
        "suite-app"
    ),
    "extensions" to listOf(
        "suite-cloudflared-module",
        "suite-diagnostics-module",
        "suite-dashboard-module",
        "suite-fn-module"
    )
)

componentGroups.forEach { (group, modules) ->
    modules.forEach { module ->
        include(":$module")
        project(":$module").projectDir = file("components/$group/$module")
    }
}
