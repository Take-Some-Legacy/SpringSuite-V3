plugins {
    `java-library`
}

description = "Typed desktop subsystem configuration and config-file contribution."

dependencies {
    api(project(":suite-config"))
    api("org.springframework.boot:spring-boot")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}
