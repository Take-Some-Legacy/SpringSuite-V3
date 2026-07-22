plugins {
    `java-library`
}

dependencies {
    api(project(":suite-core"))
    implementation(project(":suite-config"))
    implementation(project(":suite-logging"))
    implementation(project(":suite-command"))
    implementation(project(":suite-toolbelt"))
    implementation(project(":suite-workspace"))
    implementation(project(":suite-desktop-api"))
    implementation(project(":suite-desktop-helper"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
