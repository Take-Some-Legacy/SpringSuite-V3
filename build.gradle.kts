plugins {
    id("org.springframework.boot") version "3.3.6" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "com.takesome.springsuite"
    version = "0.1.5"
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
    dependsOn(":suite-diagnostics-module:signModuleJar", ":suite-dashboard-module:signModuleJar")
}

tasks.register("deploySignedModules") {
    group = "modules"
    description = "Build, sign and copy runtime modules into the external modules directory."
    dependsOn(":suite-diagnostics-module:deploySignedModule", ":suite-dashboard-module:deploySignedModule")
}
