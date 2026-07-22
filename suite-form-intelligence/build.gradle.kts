plugins {
    `java-library`
}

description = "Desktop form analysis, matching, AI/Plus planning and local safety filtering."

dependencies {
    api(project(":suite-desktop-api"))
    api(project(":suite-desktop-config"))
    implementation(project(":suite-ai-api"))
    implementation(project(":suite-ai"))
    implementation(project(":suite-toolbelt"))
    implementation(project(":suite-logging"))
    implementation(project(":suite-observability"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.springframework:spring-context")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
