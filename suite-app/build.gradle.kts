plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":suite-core"))
    implementation(project(":suite-logging"))
    implementation(project(":suite-config"))
    implementation(project(":suite-module"))
    implementation(project(":suite-cloudflared"))
    implementation(project(":suite-command"))
    implementation(project(":suite-toolbelt"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("spring-suite.jar")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
