package com.takesome.springsuite.module;

import java.util.List;

public record SuiteModuleCompatibilityReport(
        String moduleId,
        boolean active,
        SuiteModuleActivationStatus status,
        List<String> problems,
        List<SuiteModuleDependency> dependencies,
        List<SuiteModuleDependency> optionalDependencies,
        String suiteApiVersion,
        SuiteModuleIsolationPolicy isolationPolicy
) {
    public SuiteModuleCompatibilityReport {
        moduleId = moduleId == null ? "" : moduleId.trim();
        status = status == null ? SuiteModuleActivationStatus.DISABLED_INVALID_MANIFEST : status;
        problems = problems == null ? List.of() : List.copyOf(problems);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        optionalDependencies = optionalDependencies == null ? List.of() : List.copyOf(optionalDependencies);
        suiteApiVersion = suiteApiVersion == null ? "" : suiteApiVersion.trim();
        isolationPolicy = isolationPolicy == null ? SuiteModuleIsolationPolicy.SHARED_CLASSPATH : isolationPolicy;
    }
}
