plugins {
    `java-library`
}

dependencies {
    api(project(":suite-core"))
    api(project(":suite-desktop-api"))

    implementation(project(":suite-observability"))
    implementation(project(":suite-form-intelligence"))
    implementation(project(":suite-desktop-config"))
    implementation(project(":suite-browser-dom"))
    implementation(project(":suite-config"))
    implementation(project(":suite-command"))
    implementation(project(":suite-logging"))
    implementation(project(":suite-toolbelt"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
