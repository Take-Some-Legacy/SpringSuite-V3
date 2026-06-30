plugins {
    `java-library`
}

dependencies {
    api(project(":suite-core"))
    implementation(project(":suite-config"))
    implementation(project(":suite-logging"))
    implementation(project(":suite-command"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
