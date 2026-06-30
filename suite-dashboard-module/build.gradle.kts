import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":suite-module"))
    compileOnly(project(":suite-command"))
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("spring-suite-dashboard-module")
    manifest {
        attributes(
            "SpringSuite-Module" to "spring-suite-dashboard",
            "SpringSuite-Module-Version" to project.version.toString(),
            "Implementation-Title" to "SpringSuite Dashboard Module",
            "Implementation-Version" to project.version.toString()
        )
    }
}

val moduleSigningAlias = providers.gradleProperty("suite.module.signing.alias")
    .orElse("spring-suite-module-dev")
val moduleSigningStorePass = providers.gradleProperty("suite.module.signing.storePass")
    .orElse("changeit")
val moduleSigningKeyPass = providers.gradleProperty("suite.module.signing.keyPass")
    .orElse(moduleSigningStorePass)
val moduleKeystore = rootProject.layout.projectDirectory.file("suite-diagnostics-module/build/module-signing/spring-suite-module-dev.jks")
val signedModuleDir = layout.buildDirectory.dir("signed-modules")
val signedModuleJar = signedModuleDir.map { it.file(tasks.named<Jar>("jar").get().archiveFileName.get()) }

val ensureSharedModuleSigningKeystore = tasks.register<Exec>("ensureSharedModuleSigningKeystore") {
    group = "modules"
    description = "Generate the shared local development keystore used to sign runtime modules."
    outputs.file(moduleKeystore)
    onlyIf { !moduleKeystore.asFile.isFile }
    doFirst {
        moduleKeystore.asFile.parentFile.mkdirs()
    }
    commandLine(
        "keytool",
        "-genkeypair",
        "-alias", moduleSigningAlias.get(),
        "-keystore", moduleKeystore.asFile.absolutePath,
        "-storepass", moduleSigningStorePass.get(),
        "-keypass", moduleSigningKeyPass.get(),
        "-dname", "CN=SpringSuite Module Dev,O=TakeSome,OU=SuiteLab",
        "-keyalg", "RSA",
        "-keysize", "3072",
        "-validity", "3650"
    )
}

val signModuleJar = tasks.register<Exec>("signModuleJar") {
    group = "modules"
    description = "Build and sign the dashboard runtime module as an external module jar."
    dependsOn(tasks.named("jar"), ensureSharedModuleSigningKeystore)
    inputs.file(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    inputs.file(moduleKeystore)
    outputs.file(signedModuleJar)
    doFirst {
        signedModuleDir.get().asFile.mkdirs()
    }
    commandLine(
        "jarsigner",
        "-keystore", moduleKeystore.asFile.absolutePath,
        "-storepass", moduleSigningStorePass.get(),
        "-keypass", moduleSigningKeyPass.get(),
        "-signedjar", signedModuleJar.get().asFile.absolutePath,
        tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        moduleSigningAlias.get()
    )
}

tasks.register<Copy>("deploySignedModule") {
    group = "modules"
    description = "Copy the signed dashboard module jar into the external runtime modules directory."
    dependsOn(signModuleJar)
    from(signedModuleJar)
    into(rootProject.layout.projectDirectory.dir("modules"))
}
