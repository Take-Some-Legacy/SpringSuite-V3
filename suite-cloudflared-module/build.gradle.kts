plugins {
    `java-library`
}

dependencies {
    implementation(project(":suite-core"))
    implementation(project(":suite-command"))
    implementation(project(":suite-cloudflared"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
