package com.takesome.springsuite.module;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

public record SuiteModuleBootstrapResult(
        Path runtimeRoot,
        Path modulesDir,
        boolean enabled,
        boolean recursive,
        List<Path> moduleJars,
        List<SuiteModuleJarTrustReport> trustReports,
        URLClassLoader classLoader
) {
    public SuiteModuleBootstrapResult {
        moduleJars = moduleJars == null ? List.of() : List.copyOf(moduleJars);
        trustReports = trustReports == null ? List.of() : List.copyOf(trustReports);
    }
}
