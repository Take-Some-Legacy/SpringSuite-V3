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

include(
    "suite-core",
    "suite-ai-api",
    "suite-platform",
    "suite-desktop-api",
    "suite-desktop-config",
    "suite-observability",
    "suite-form-intelligence",
    "suite-browser-dom",
    "suite-logging",
    "suite-database",
    "suite-config",
    "suite-module",
    "suite-cloudflared",
    "suite-cloudflared-module",
    "suite-command",
    "suite-toolbelt",
    "suite-workspace",
    "suite-ai",
    "suite-openai",
    "suite-desktop-helper",
    "suite-agent",
    "suite-app",
    "suite-diagnostics-module",
    "suite-dashboard-module",
    "suite-fn-module"
)
