plugins {
    `java-library`
}

dependencies {
    api(project(":suite-core"))
    api(project(":suite-config"))
    api(project(":suite-command"))
    implementation(project(":suite-logging"))
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.81")
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
