plugins {
    `java-library`
}

description = "Browser DOM form recognition and operator-confirmed fill transport."

dependencies {
    api(project(":suite-desktop-api"))
    implementation(project(":suite-core"))
    implementation(project(":suite-logging"))
    implementation(project(":suite-observability"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
