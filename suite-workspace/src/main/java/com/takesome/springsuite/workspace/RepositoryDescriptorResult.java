package com.takesome.springsuite.workspace;

import java.util.Map;

public record RepositoryDescriptorResult(
        boolean ok,
        String repositoryRoot,
        String descriptorPath,
        boolean created,
        boolean updated,
        Map<String, Object> descriptor,
        String message
) {
    public RepositoryDescriptorResult {
        repositoryRoot = repositoryRoot == null ? "" : repositoryRoot;
        descriptorPath = descriptorPath == null ? "" : descriptorPath;
        descriptor = descriptor == null ? Map.of() : Map.copyOf(descriptor);
        message = message == null ? "" : message;
    }
}
