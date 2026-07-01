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
    "suite-logging",
    "suite-config",
    "suite-module",
    "suite-cloudflared",
    "suite-cloudflared-module",
    "suite-command",
    "suite-toolbelt",
    "suite-workspace",
    "suite-agent",
    "suite-app",
    "suite-diagnostics-module",
    "suite-dashboard-module"
)
