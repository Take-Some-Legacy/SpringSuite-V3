import groovy.json.JsonOutput
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("org.springframework.boot") version "3.3.6" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "com.takesome.springsuite"
    version = "0.2.0"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.6")
        }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

tasks.register("buildSignedModules") {
    group = "modules"
    description = "Build signed SpringSuite runtime modules without embedding them into the core application."
    dependsOn(":suite-diagnostics-module:signModuleJar", ":suite-dashboard-module:signModuleJar", ":suite-fn-module:signModuleJar")
}

tasks.register("deploySignedModules") {
    group = "modules"
    description = "Build, sign and copy runtime modules into the external modules directory."
    dependsOn(":suite-diagnostics-module:deploySignedModule", ":suite-dashboard-module:deploySignedModule", ":suite-fn-module:deploySignedModule")
}


val deployDirectory = layout.buildDirectory.dir("deploy")

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

tasks.register<Sync>("assembleDeploy") {
    group = "distribution"
    description = "Assemble a portable SpringSuite runtime under build/deploy."
    dependsOn(":suite-app:bootJar", "buildSignedModules")

    into(deployDirectory)

    from(layout.projectDirectory.file("suite-app/build/libs/spring-suite.jar"))
    from(layout.projectDirectory.file("README.md"))
    from(layout.projectDirectory.files("run.bat", "run-console.bat", "run-elevated.bat", "run.sh", "run-elevated.sh"))
    from(layout.projectDirectory.dir("scripts")) {
        into("scripts")
        exclude("*.local.*", "*.tmp", "*.bak-*")
    }
    from(layout.projectDirectory.file("docs/operations/deployment.md")) {
        into("docs")
        rename { "DEPLOYMENT.md" }
    }

    from(layout.projectDirectory.dir("static")) {
        into("static")
    }
    from(layout.projectDirectory.dir("browser-extension")) {
        into("browser-extension")
    }
    from(layout.projectDirectory.dir("config")) {
        into("config")
        exclude("**/*.bak-*", "**/*.bak", "**/*~")
    }
    from(layout.projectDirectory.dir("tools")) {
        into("tools")
    }
    from(layout.projectDirectory.dir("suiteBinaries")) {
        into("suiteBinaries")
        exclude("*-debug.exe")
    }
    from(layout.projectDirectory.dir("suite-dashboard-module/build/signed-modules")) {
        include("*.jar")
        into("modules")
    }
    from(layout.projectDirectory.dir("suite-diagnostics-module/build/signed-modules")) {
        include("*.jar")
        into("modules")
    }
    from(layout.projectDirectory.dir("suite-fn-module/build/signed-modules")) {
        include("*.jar")
        into("modules")
    }

    doLast {
        val root = deployDirectory.get().asFile
        listOf("data", "logs", "logs/archive", "logs/crash", ".springsuite").forEach { relative ->
            File(root, relative).mkdirs()
        }

        val modulesConfig = File(root, "config/suite-modules.yml")
        if (modulesConfig.isFile) {
            val portable = modulesConfig.readText(StandardCharsets.UTF_8)
                .replace(Regex("(?m)^(\\s*directory:\\s*).*$"), "$1\"modules\"")
            modulesConfig.writeText(portable, StandardCharsets.UTF_8)
        }

        val desktopConfig = File(root, "config/suite-desktop-helper.yml")
        if (desktopConfig.isFile) {
            val portable = desktopConfig.readText(StandardCharsets.UTF_8)
                .replace(Regex("(?m)^(\\s*project-root:\\s*).*$"), "$1.")
            desktopConfig.writeText(portable, StandardCharsets.UTF_8)
        }

        File(root, "DEPLOYMENT.txt").writeText(
            """
            SpringSuite ${project.version}

            Запуск в системный трей:
              run.bat

            Запуск с видимой консолью:
              run-console.bat

            Запуск с повышенными правами:
              run-elevated.bat

            Требования:
              - Windows 10/11 x64
              - Java 17 или новее

            Рабочие каталоги data, logs и .springsuite создаются локально внутри этой директории.
            Нативный desktop-agent расположен в suiteBinaries/suite-desktop-agent.exe.
            Browser Form Bridge расположен в browser-extension/springsuite-form-bridge/.
            Подписанные runtime-модули расположены в modules/.

            При необработанной ошибке SpringSuite показывает аварийное окно с полным текстом
            и сохраняет отчёт в logs/crash/spring-suite-crash-<дата-время>.txt.
            """.trimIndent() + System.lineSeparator(),
            StandardCharsets.UTF_8
        )

        File(root, "CRASH-REPORTING.txt").writeText(
            """
            SPRINGSUITE CRASH REPORTING
            ===========================

            При необработанной ошибке SpringSuite показывает окно с полным текстом для анализа.

            В окне доступны кнопки:
              - Копировать текст
              - Открыть папку отчёта
              - Перезапустить SpringSuite
              - Закрыть SpringSuite / Закрыть окно

            Текстовые отчёты:
              logs/crash/spring-suite-crash-<дата-время>.txt

            Нативные отчёты JVM:
              logs/crash/hs_err_pid<pid>.log

            Отчёт содержит exception, полный stack trace, build/runtime сведения,
            thread dump и последние 64 KiB logs/spring-suite.log.
            """.trimIndent() + System.lineSeparator(),
            StandardCharsets.UTF_8
        )

        val manifestFile = File(root, "deploy-manifest.json")
        val files = root.walkTopDown()
            .filter { it.isFile && it != manifestFile }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .map { file ->
                mapOf(
                    "path" to file.relativeTo(root).invariantSeparatorsPath,
                    "size" to file.length(),
                    "sha256" to sha256(file)
                )
            }
            .toList()
        val manifest = linkedMapOf<String, Any>(
            "schema" to "spring-suite.deploy-manifest.v1",
            "version" to project.version.toString(),
            "builtAt" to Instant.now().toString(),
            "fileCount" to files.size,
            "files" to files
        )
        manifestFile.writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + System.lineSeparator(),
            StandardCharsets.UTF_8
        )
    }
}

tasks.register("verifyDeployLayout") {
    group = "verification"
    description = "Verify that build/deploy contains a complete runnable SpringSuite image."
    dependsOn("assembleDeploy")

    doLast {
        val root = deployDirectory.get().asFile
        val requiredFiles = listOf(
            "spring-suite.jar",
            "run.bat",
            "run-console.bat",
            "run-elevated.bat",
            "run.sh",
            "run-elevated.sh",
            "scripts/deploy.ps1",
            "scripts/apply-deploy.ps1",
            "scripts/clean.ps1",
            "scripts/verify-repository.ps1",
            "scripts/spring-suite-single-instance-check.ps1",
            "config/suite-cloudflared.yml",
            "suiteBinaries/suite-cloudflared-wrapper.exe",
            "deploy-manifest.json"
        )
        val missing = requiredFiles.filterNot { File(root, it).isFile }
        check(missing.isEmpty()) {
            "Incomplete SpringSuite deploy image; missing: ${missing.joinToString()}"
        }

        val jar = File(root, "spring-suite.jar")
        check(jar.length() > 1_000_000L) {
            "spring-suite.jar is unexpectedly small: ${jar.length()} bytes"
        }

        val cloudflaredConfig = File(root, "config/suite-cloudflared.yml").readText(StandardCharsets.UTF_8)
        check(Regex("""(?m)^\s*enabled:\s*true\s*$""").containsMatchIn(cloudflaredConfig)) {
            "deploy cloudflared configuration must enable the service"
        }
        check(Regex("""(?m)^\s*auto-start:\s*true\s*$""").containsMatchIn(cloudflaredConfig)) {
            "deploy cloudflared configuration must enable autostart"
        }
    }
}

tasks.register<Zip>("packageDeploy") {
    group = "distribution"
    description = "Create a ZIP archive from build/deploy."
    dependsOn("assembleDeploy")
    archiveFileName.set("spring-suite-${project.version}-windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(deployDirectory) {
        into("SpringSuite")
    }
}
