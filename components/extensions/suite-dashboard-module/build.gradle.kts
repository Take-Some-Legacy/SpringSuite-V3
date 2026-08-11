plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":suite-module"))
    compileOnly(project(":suite-command"))
}
