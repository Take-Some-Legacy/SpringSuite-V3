import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("org.springframework.boot")
}

fun commandOutput(vararg command: String): String {
    return try {
        val process = ProcessBuilder(*command)
            .directory(rootProject.projectDir as File)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) {
            output
        } else {
            process.destroyForcibly()
            "unknown"
        }
    } catch (_: Exception) {
        "unknown"
    }
}

val buildTime = Instant.now().toString()
val gitCommit = commandOutput("git", "rev-parse", "--short=12", "HEAD")
val gitBranch = commandOutput("git", "rev-parse", "--abbrev-ref", "HEAD")
val gitStatus = commandOutput("git", "status", "--porcelain")
val gitDirty = when (gitStatus) {
    "unknown" -> "unknown"
    "" -> "false"
    else -> "true"
}
val localBuildId = "local-" + buildTime.filter(Char::isDigit).take(14)
val buildIdentifier = providers.environmentVariable("BUILD_NUMBER")
    .orElse(providers.environmentVariable("GITHUB_RUN_NUMBER"))
    .orElse(providers.provider {
        gitCommit.takeIf { it.isNotBlank() && it != "unknown" } ?: localBuildId
    })

springBoot {
    buildInfo {
        properties {
            name = "spring-suite"
            version = project.version.toString()
            time = buildTime
            additional = mapOf(
                "build" to buildIdentifier.get(),
                "commit" to gitCommit,
                "branch" to gitBranch,
                "dirty" to gitDirty
            )
        }
    }
}

dependencies {
    implementation(project(":suite-core"))
    implementation(project(":suite-logging"))
    implementation(project(":suite-config"))
    implementation(project(":suite-module"))
    implementation(project(":suite-cloudflared"))
    implementation(project(":suite-command"))
    implementation(project(":suite-toolbelt"))
    implementation(project(":suite-workspace"))
    implementation(project(":suite-agent"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("spring-suite.jar")
}

tasks.named<BootRun>("bootRun") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
