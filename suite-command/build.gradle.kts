plugins {
    `java-library`
}

dependencies {
    api(project(":suite-core"))
    implementation(project(":suite-config"))
    implementation(project(":suite-logging"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jline:jline:3.27.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
