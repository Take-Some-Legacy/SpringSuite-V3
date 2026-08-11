package com.takesome.springsuite.workspace;

import java.util.List;

public record RepositoryDescriptorCatalog(
        boolean ok,
        String currentRepositoryRoot,
        String currentDescriptorPath,
        String cachePath,
        int repositoryCount,
        int cachedRepositoryCount,
        List<String> cachedRepositoryRoots,
        List<RepositoryDescriptorResult> repositories,
        String message
) {
    public RepositoryDescriptorCatalog {
        currentRepositoryRoot = currentRepositoryRoot == null ? "" : currentRepositoryRoot;
        currentDescriptorPath = currentDescriptorPath == null ? "" : currentDescriptorPath;
        cachePath = cachePath == null ? "" : cachePath;
        cachedRepositoryRoots = cachedRepositoryRoots == null ? List.of() : List.copyOf(cachedRepositoryRoots);
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        message = message == null ? "" : message;
    }
}
