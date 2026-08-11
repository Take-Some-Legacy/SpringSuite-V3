plugins {
    `java-library`
}

dependencies {
    api(project(":suite-core"))
    implementation(project(":suite-config"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.fusesource.jansi:jansi:2.4.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
