import org.gradle.jvm.tasks.Jar

val moduleId = extensions.extraProperties["springSuiteModuleId"] as String
val moduleTitle = extensions.extraProperties["springSuiteModuleTitle"] as String
val deployDirectoryProperty = extensions.extraProperties.properties["springSuiteModuleDeployDirectoryProperty"] as? String
val moduleSigningAlias = providers.gradleProperty("suite.module.signing.alias")
    .orElse("spring-suite-module-dev")
val moduleSigningStorePass = providers.gradleProperty("suite.module.signing.storePass")
    .orElse("changeit")
val moduleSigningKeyPass = providers.gradleProperty("suite.module.signing.keyPass")
    .orElse(moduleSigningStorePass)
val moduleKeystore = rootProject.layout.buildDirectory.file("module-signing/spring-suite-module-dev.jks")
val signedModuleDir = layout.buildDirectory.dir("signed-modules")
val signedModuleJar = signedModuleDir.map { directory ->
    directory.file(tasks.named<Jar>("jar").get().archiveFileName.get())
}
val localModulesDirectory = providers.provider {
    rootProject.layout.projectDirectory.dir("modules").asFile.absolutePath
}
val deployDirectoryPath = deployDirectoryProperty
    ?.let { propertyName -> providers.gradleProperty(propertyName).orElse(localModulesDirectory) }
    ?: localModulesDirectory

tasks.named<Jar>("jar") {
    archiveBaseName.set("spring-${project.name}")
    manifest {
        attributes(
            "SpringSuite-Module" to moduleId,
            "SpringSuite-Module-Version" to project.version.toString(),
            "Implementation-Title" to moduleTitle,
            "Implementation-Version" to project.version.toString()
        )
    }
}

tasks.register<Exec>("signModuleJar") {
    group = "modules"
    description = "Build and sign ${project.name} as an external SpringSuite runtime module."
    dependsOn(tasks.named("jar"), rootProject.tasks.named("ensureModuleSigningKeystore"))
    inputs.file(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    inputs.file(moduleKeystore)
    outputs.file(signedModuleJar)
    doFirst {
        signedModuleDir.get().asFile.mkdirs()
    }
    commandLine(
        "jarsigner",
        "-keystore", moduleKeystore.get().asFile.absolutePath,
        "-storepass", moduleSigningStorePass.get(),
        "-keypass", moduleSigningKeyPass.get(),
        "-signedjar", signedModuleJar.get().asFile.absolutePath,
        tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        moduleSigningAlias.get()
    )
}

tasks.register<Copy>("deploySignedModule") {
    group = "modules"
    description = "Copy the signed ${project.name} jar into its configured runtime modules directory."
    dependsOn(tasks.named("signModuleJar"))
    from(signedModuleJar)
    into(deployDirectoryPath.map { file(it) })
}
