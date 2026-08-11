plugins {

    `java-library`

}



dependencies {

    api(project(":suite-core"))
    api(project(":suite-ai-api"))

    implementation(project(":suite-config"))

    implementation(project(":suite-command"))

    implementation(project(":suite-logging"))

    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

}
