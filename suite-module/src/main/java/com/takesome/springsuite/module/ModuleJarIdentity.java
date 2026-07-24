package com.takesome.springsuite.module;

import java.nio.file.Path;

record ModuleJarIdentity(Path path, String moduleId, String version) {
    ModuleJarIdentity {
        path = path.toAbsolutePath().normalize();
        moduleId = moduleId == null ? "" : moduleId.trim();
        version = version == null ? "" : version.trim();
    }
}
