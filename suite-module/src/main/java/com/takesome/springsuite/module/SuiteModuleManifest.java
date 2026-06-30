package com.takesome.springsuite.module;

import java.util.List;
import java.util.Map;

public record SuiteModuleManifest(
        String id,
        String name,
        String version,
        String vendor,
        String description,
        List<String> dependencies,
        List<String> optionalDependencies,
        Map<String, Object> metadata,
        String suiteApiVersion,
        SuiteModuleIsolationPolicy isolationPolicy
) {
    public static final String CURRENT_SUITE_API_VERSION = "1";

    public SuiteModuleManifest(
            String id,
            String name,
            String version,
            String vendor,
            String description,
            List<String> dependencies,
            List<String> optionalDependencies,
            Map<String, Object> metadata
    ) {
        this(id, name, version, vendor, description, dependencies, optionalDependencies, metadata,
                CURRENT_SUITE_API_VERSION, SuiteModuleIsolationPolicy.SHARED_CLASSPATH);
    }

    public SuiteModuleManifest {
        id = id == null || id.isBlank() ? "unknown" : id.trim();
        name = name == null || name.isBlank() ? id : name.trim();
        version = version == null || version.isBlank() ? "0.0.0" : version.trim();
        vendor = vendor == null ? "" : vendor.trim();
        description = description == null ? "" : description.trim();
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        optionalDependencies = optionalDependencies == null ? List.of() : List.copyOf(optionalDependencies);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        suiteApiVersion = suiteApiVersion == null || suiteApiVersion.isBlank() ? CURRENT_SUITE_API_VERSION : suiteApiVersion.trim();
        isolationPolicy = isolationPolicy == null ? SuiteModuleIsolationPolicy.SHARED_CLASSPATH : isolationPolicy;
    }
}
