import groovy.json.JsonOutput
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.jar.JarFile
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip

val suiteVersion = providers.gradleProperty("suiteVersion").get()

plugins {
    id("org.springframework.boot") version "3.3.6" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "com.takesome.springsuite"
    version = suiteVersion
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

data class SignedRuntimeModuleSpec(
    val projectPath: String,
    val moduleId: String,
    val title: String,
    val deployDirectoryProperty: String? = null
)

val signedRuntimeModules = listOf(
    SignedRuntimeModuleSpec(
        projectPath = ":suite-diagnostics-module",
        moduleId = "spring-suite-diagnostics",
        title = "SpringSuite Diagnostics Module"
    ),
    SignedRuntimeModuleSpec(
        projectPath = ":suite-dashboard-module",
        moduleId = "spring-suite-dashboard",
        title = "SpringSuite Dashboard Module"
    ),
    SignedRuntimeModuleSpec(
        projectPath = ":suite-fn-module",
        moduleId = "spring-suite-fn",
        title = "SpringSuite FN Operator Module",
        deployDirectoryProperty = "suite.v3.modules.dir"
    )
)

val moduleSigningAlias = providers.gradleProperty("suite.module.signing.alias")
    .orElse("spring-suite-module-dev")
val moduleSigningStorePass = providers.gradleProperty("suite.module.signing.storePass")
    .orElse("changeit")
val moduleSigningKeyPass = providers.gradleProperty("suite.module.signing.keyPass")
    .orElse(moduleSigningStorePass)
val moduleSigningKeystore = layout.buildDirectory.file("module-signing/spring-suite-module-dev.jks")

tasks.register<Exec>("ensureModuleSigningKeystore") {
    group = "modules"
    description = "Generate the shared local development keystore used by signed runtime modules."
    outputs.file(moduleSigningKeystore)
    onlyIf { !moduleSigningKeystore.get().asFile.isFile }
    doFirst { moduleSigningKeystore.get().asFile.parentFile.mkdirs() }
    commandLine(
        "keytool",
        "-genkeypair",
        "-alias", moduleSigningAlias.get(),
        "-keystore", moduleSigningKeystore.get().asFile.absolutePath,
        "-storepass", moduleSigningStorePass.get(),
        "-keypass", moduleSigningKeyPass.get(),
        "-dname", "CN=SpringSuite Module Dev,O=TakeSome,OU=SuiteLab",
        "-keyalg", "RSA",
        "-keysize", "3072",
        "-validity", "3650"
    )
}

signedRuntimeModules.forEach { spec ->
    val moduleProject = project(spec.projectPath)
    moduleProject.extensions.extraProperties["springSuiteModuleId"] = spec.moduleId
    moduleProject.extensions.extraProperties["springSuiteModuleTitle"] = spec.title
    spec.deployDirectoryProperty?.let {
        moduleProject.extensions.extraProperties["springSuiteModuleDeployDirectoryProperty"] = it
    }
    moduleProject.apply(mapOf("from" to rootProject.file("gradle/runtime-module-signing.gradle.kts")))
}

tasks.register("buildSignedModules") {
    group = "modules"
    description = "Build signed SpringSuite runtime modules without embedding them into the core application."
    dependsOn(signedRuntimeModules.map { "${it.projectPath}:signModuleJar" })
}

tasks.register("deploySignedModules") {
    group = "modules"
    description = "Build, sign and copy runtime modules into configured external module directories."
    dependsOn(signedRuntimeModules.map { "${it.projectPath}:deploySignedModule" })
}


val deployDirectoryName = providers.gradleProperty("deployDirectoryName").getOrElse("deploy")
val deployDirectory = layout.buildDirectory.dir(deployDirectoryName)

val runtimeControlPlaneBinaryNames = listOf(
    "suite-runtime-controller.exe",
    "suite-runtime-replacer.exe",
    "suite-runtime-bootstrap.exe",
    "suite-runtime-console.exe",
    "suite-runtime-preloader.exe",
    "suite-runtime-toast.exe",
    "suite-runtime-tray.exe",
    "suite-runtime-toast-host.exe"
)
val runtimeControlPlaneRootPath = providers.gradleProperty("runtimeControlPlaneRoot")
    .orElse(providers.environmentVariable("SPRING_SUITE_RUNTIME_CONTROLLER_ROOT"))
    .orElse(layout.projectDirectory.dir("../suite-runtime-controller-go").asFile.absolutePath)
val runtimeControlPlaneRoot = provider { file(runtimeControlPlaneRootPath.get()) }
val runtimeControlPlaneDist = provider { File(runtimeControlPlaneRoot.get(), "dist") }
val buildRuntimeControlPlane = tasks.register<Exec>("buildRuntimeControlPlane") {
    group = "distribution"
    description = "Build the sibling Go/C++ runtime control plane when its source repository is available."
    val root = runtimeControlPlaneRoot.get()
    val buildScript = File(root, "scripts/build.ps1")
    onlyIf { System.getProperty("os.name").lowercase().contains("windows") && buildScript.isFile }
    workingDir(root)
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        buildScript.absolutePath,
        "-Output",
        runtimeControlPlaneDist.get().absolutePath,
        "-RequireNativeToast"
    )
}
val runtimeControlPlaneBinarySource = provider {
    val built = runtimeControlPlaneDist.get()
    if (runtimeControlPlaneBinaryNames.all { File(built, it).isFile }) {
        built
    } else {
        layout.projectDirectory.dir("suiteBinaries").asFile
    }
}

val cloudflaredWrapperBinaryName = "suite-cloudflared-wrapper.exe"
val cloudflaredWrapperRootPath = providers.gradleProperty("cloudflaredWrapperRoot")
    .orElse(providers.environmentVariable("SPRING_SUITE_CLOUDFLARED_WRAPPER_ROOT"))
    .orElse(layout.projectDirectory.dir("../suite-cloudflared-wrapper-go").asFile.absolutePath)
val cloudflaredWrapperRoot = provider { file(cloudflaredWrapperRootPath.get()) }
val cloudflaredWrapperBuild = provider { File(cloudflaredWrapperRoot.get(), "build/$cloudflaredWrapperBinaryName") }
val buildCloudflaredWrapper = tasks.register<Exec>("buildCloudflaredWrapper") {
    group = "distribution"
    description = "Build the sibling cloudflared wrapper instead of packaging a stale checked-in binary."
    val root = cloudflaredWrapperRoot.get()
    val buildScript = File(root, "scripts/build.bat")
    onlyIf { System.getProperty("os.name").lowercase().contains("windows") && buildScript.isFile }
    workingDir(root)
    commandLine("cmd.exe", "/d", "/c", buildScript.absolutePath)
}
val cloudflaredWrapperBinarySource = provider {
    val built = cloudflaredWrapperBuild.get()
    if (built.isFile) built else File(layout.projectDirectory.dir("suiteBinaries").asFile, cloudflaredWrapperBinaryName)
}

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
    dependsOn(":suite-app:bootJar", "buildSignedModules", buildRuntimeControlPlane, buildCloudflaredWrapper)

    into(deployDirectory)

    from(project(":suite-app").layout.buildDirectory.file("libs/spring-suite.jar"))
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
        exclude(runtimeControlPlaneBinaryNames)
        exclude(cloudflaredWrapperBinaryName)
    }
    from(cloudflaredWrapperBinarySource) {
        into("suiteBinaries")
    }
    from(runtimeControlPlaneBinarySource) {
        into("suiteBinaries")
        include(runtimeControlPlaneBinaryNames)
    }
    signedRuntimeModules.forEach { spec ->
        from(project(spec.projectPath).layout.buildDirectory.dir("signed-modules")) {
            include("*-$suiteVersion.jar")
            into("modules")
        }
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
            Go runtime control plane расположен в suiteBinaries/suite-runtime-controller.exe.
            Его portable-конфигурация: config/runtime-controller.json.
            Подготовка service/toast integration: scripts/install-runtime-controller.ps1.
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
            "preservedRoots" to listOf("data", "logs", ".springsuite", "authority"),
            "controlPlaneFiles" to runtimeControlPlaneBinaryNames.map { "suiteBinaries/$it" },
            "fileCount" to files.size,
            "files" to files
        )
        manifestFile.writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + System.lineSeparator(),
            StandardCharsets.UTF_8
        )
    }
}

tasks.register("verifyModuleBoundaries") {
    group = "verification"
    description = "Verify SpringSuite module dependency direction and contract purity."

    doLast {
        val projectDependencyPattern = Regex("""project\(\":([^\"]+)\"\)""")
        val forbiddenEdges = mapOf(
            "suite-core" to emptySet(),
            "suite-ai-api" to emptySet(),
            "suite-desktop-api" to emptySet(),
            "suite-platform" to emptySet(),
            "suite-observability" to emptySet(),
            "suite-desktop-config" to setOf("suite-desktop-helper", "suite-form-intelligence", "suite-browser-dom", "suite-agent", "suite-app"),
            "suite-form-intelligence" to setOf("suite-browser-dom", "suite-desktop-helper", "suite-agent", "suite-app", "suite-openai"),
            "suite-browser-dom" to setOf("suite-form-intelligence", "suite-desktop-helper", "suite-agent", "suite-app", "suite-openai")
        )
        val errors = mutableListOf<String>()

        forbiddenEdges.forEach { (module, forbidden) ->
            val buildFile = project(":$module").layout.projectDirectory.file("build.gradle.kts").asFile
            check(buildFile.isFile) { "Missing module build file: ${buildFile.path}" }
            val dependencies = projectDependencyPattern.findAll(buildFile.readText(StandardCharsets.UTF_8))
                .map { it.groupValues[1] }
                .toSet()
            if (forbidden.isEmpty() && dependencies.isNotEmpty()) {
                errors += "$module must remain dependency-free but depends on ${dependencies.sorted().joinToString()}"
            } else {
                val invalid = dependencies.intersect(forbidden)
                if (invalid.isNotEmpty()) {
                    errors += "$module has forbidden dependencies: ${invalid.sorted().joinToString()}"
                }
            }
        }

        val forbiddenSourceTokens = mapOf(
            "suite-core" to listOf("org.springframework", "com.takesome.springsuite.ai", "com.takesome.springsuite.desktop"),
            "suite-ai-api" to listOf("org.springframework"),
            "suite-desktop-api" to listOf("org.springframework"),
            "suite-platform" to listOf("org.springframework"),
            "suite-form-intelligence" to listOf(
                "BrowserDomService",
                "DesktopAgentService",
                "DesktopAgentUi",
                "com.takesome.springsuite.openai"
            ),
            "suite-browser-dom" to listOf(
                "DesktopAgentService",
                "DesktopAgentUi",
                "DesktopBridgeService",
                "com.takesome.springsuite.openai"
            )
        )
        forbiddenSourceTokens.forEach { (module, tokens) ->
            val sourceRoot = project(":$module").layout.projectDirectory.dir("src/main/java").asFile
            if (!sourceRoot.isDirectory) return@forEach
            sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { source ->
                    val content = source.readText(StandardCharsets.UTF_8)
                    tokens.forEach { token ->
                        if (content.contains(token)) {
                            errors += "$module source ${source.relativeTo(projectDir)} contains forbidden token: $token"
                        }
                    }
                }
        }

        check(errors.isEmpty()) {
            "Module boundary violations:\n - " + errors.joinToString("\n - ")
        }
        logger.lifecycle("Module boundary verification passed for ${forbiddenEdges.size} modules.")
    }
}

val verifyVersionConsistency = tasks.register("verifyVersionConsistency") {
    group = "verification"
    description = "Verify Java and native Windows resources use the same SpringSuite release version."

    doLast {
        val nativeVersion = "$suiteVersion.0"
        val mismatches = mutableListOf<String>()
        val nativeRoot = layout.projectDirectory.dir("native/go").asFile
        if (nativeRoot.isDirectory) {
            nativeRoot.walkTopDown()
                .filter { it.isFile && it.name == "winres.json" }
                .forEach { resource ->
                    val content = resource.readText(StandardCharsets.UTF_8)
                    if (!content.contains("\"version\": \"$nativeVersion\"") ||
                        !content.contains("\"product_version\": \"$nativeVersion\"")) {
                        mismatches += resource.relativeTo(projectDir).invariantSeparatorsPath
                    }
                }
        }
        val resourceIndex = layout.projectDirectory.file("windows-resources-index.json").asFile
        if (!resourceIndex.readText(StandardCharsets.UTF_8).contains("\"version\": \"$nativeVersion\"")) {
            mismatches += resourceIndex.relativeTo(projectDir).invariantSeparatorsPath
        }
        check(mismatches.isEmpty()) {
            "Version mismatch: expected $nativeVersion in ${mismatches.joinToString()}"
        }
    }
}

val verifyBrowserNotifications = tasks.register<Exec>("verifyBrowserNotifications") {
    group = "verification"
    description = "Run deterministic BrowserNotification lifetime and rotation tests."
    val appProject = project(":suite-app")
    workingDir(appProject.projectDir)
    commandLine("node", "--test", "src/test/js/browser-notifications.test.js")
}

tasks.register("verifyDeployLayout") {
    group = "verification"
    description = "Verify that build/deploy contains a complete runnable SpringSuite image."
    dependsOn("assembleDeploy", "verifyModuleBoundaries", verifyVersionConsistency, verifyBrowserNotifications)

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
            "scripts/spring-suite-supervisor.ps1",
            "scripts/suite-toast.ps1",
            "scripts/install-runtime-controller.ps1",
            "scripts/clean.ps1",
            "scripts/verify-repository.ps1",
            "scripts/spring-suite-single-instance-check.ps1",
            "config/suite-cloudflared.yml",
            "config/runtime-controller.json",
            "static/index.html",
            "static/js/browser-notifications.js",
            "suiteBinaries/suite-cloudflared-wrapper.exe",
            "suiteBinaries/suite-runtime-controller.exe",
            "suiteBinaries/suite-runtime-replacer.exe",
            "suiteBinaries/suite-runtime-bootstrap.exe",
            "suiteBinaries/suite-runtime-console.exe",
            "suiteBinaries/suite-runtime-toast.exe",
            "suiteBinaries/suite-runtime-toast-host.exe",
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

        val moduleJars = File(root, "modules").listFiles { file -> file.isFile && file.extension.equals("jar", true) }
            ?.sortedBy { it.name }
            ?: emptyList()
        check(moduleJars.size == 3) {
            "deploy image must contain exactly three runtime module JARs for $suiteVersion, found: ${moduleJars.map { it.name }}"
        }
        val moduleIds = moduleJars.map { moduleJar ->
            check(moduleJar.name.endsWith("-$suiteVersion.jar")) {
                "stale or mismatched module JAR in deploy image: ${moduleJar.name}; expected version $suiteVersion"
            }
            JarFile(moduleJar, false).use { jarFile ->
                val attributes = jarFile.manifest?.mainAttributes
                    ?: error("module JAR has no manifest: ${moduleJar.name}")
                val id = attributes.getValue("SpringSuite-Module")?.trim().orEmpty()
                val version = attributes.getValue("SpringSuite-Module-Version")?.trim().orEmpty()
                check(id.isNotBlank()) { "module JAR is missing SpringSuite-Module: ${moduleJar.name}" }
                check(version == suiteVersion) {
                    "module JAR ${moduleJar.name} declares version $version, expected $suiteVersion"
                }
                id.lowercase()
            }
        }
        check(moduleIds.distinct().size == moduleIds.size) {
            "deploy image contains duplicate SpringSuite module IDs: $moduleIds"
        }

        val cloudflaredConfig = File(root, "config/suite-cloudflared.yml").readText(StandardCharsets.UTF_8)
        check(Regex("""(?m)^\s*enabled:\s*true\s*$""").containsMatchIn(cloudflaredConfig)) {
            "deploy cloudflared configuration must enable the service"
        }
        check(Regex("""(?m)^\s*auto-start:\s*false\s*$""").containsMatchIn(cloudflaredConfig)) {
            "deploy cloudflared configuration must disable JVM autostart because runtime controller owns the tunnel"
        }
        check(Regex("""(?m)^\s*wrapper-enabled:\s*true\s*$""").containsMatchIn(cloudflaredConfig)) {
            "deploy cloudflared configuration must use the suite wrapper"
        }
        check(!Regex("""(?m)^\s*tunnel-name:\s*auto\s*$""").containsMatchIn(cloudflaredConfig)) {
            "deploy cloudflared configuration must not use tunnel-name: auto"
        }
        check(cloudflaredConfig.contains("626b902a-712c-4932-b7a4-f6daf7512696")) {
            "deploy cloudflared configuration must contain the named tunnel id"
        }
        check(cloudflaredConfig.contains("credentials-file:")) {
            "deploy cloudflared configuration must declare the credentials file"
        }

        val runtimeControllerConfig = File(root, "config/runtime-controller.json").readText(StandardCharsets.UTF_8)
        check(Regex("""(?s)"cloudflared"\s*:\s*\{.*?"enabled"\s*:\s*true""").containsMatchIn(runtimeControllerConfig)) {
            "runtime controller configuration must enable controller-owned cloudflared"
        }
        check(Regex("""(?s)"cloudflared"\s*:\s*\{.*?"required"\s*:\s*true""").containsMatchIn(runtimeControllerConfig)) {
            "runtime controller configuration must require cloudflared before READY"
        }
        check(runtimeControllerConfig.contains("--suite.cloudflared.auto-start=false")) {
            "runtime controller configuration must disable JVM cloudflared autostart"
        }
        check(!Regex("""(?s)"cloudflared"\s*:\s*\{.*?"tunnel"\s*:\s*"auto"""").containsMatchIn(runtimeControllerConfig)) {
            "runtime controller configuration must use an explicit named tunnel id, not auto"
        }
        check(runtimeControllerConfig.contains("626b902a-712c-4932-b7a4-f6daf7512696")) {
            "runtime controller configuration must contain the named tunnel id"
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
