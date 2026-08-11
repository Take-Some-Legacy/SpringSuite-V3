plugins {
    `java-library`
}

description = "Bounded-cardinality metrics and operation tracing facade for SpringSuite."

dependencies {
    api("io.micrometer:micrometer-core")
    implementation("org.springframework:spring-context")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
