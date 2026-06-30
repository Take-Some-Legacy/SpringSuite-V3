package com.takesome.springsuite.config;

public record SuiteConfigFile(
        String moduleId,
        String fileName,
        String defaultResource,
        int order
) {
    public SuiteConfigFile {
        moduleId = moduleId == null || moduleId.isBlank() ? "unknown" : moduleId.trim();
        fileName = fileName == null || fileName.isBlank() ? moduleId + ".yml" : fileName.trim();
        defaultResource = defaultResource == null || defaultResource.isBlank() ? fileName : defaultResource.trim();
    }
}
