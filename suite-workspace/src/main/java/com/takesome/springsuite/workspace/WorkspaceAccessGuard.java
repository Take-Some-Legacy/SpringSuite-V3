package com.takesome.springsuite.workspace;

final class WorkspaceAccessGuard {
    private final WorkspaceProperties properties;

    WorkspaceAccessGuard(WorkspaceProperties properties) {
        this.properties = properties;
    }

    void ensureRead() {
        ensureEnabled();
        if (!properties.effectiveAllowRead()) {
            throw new IllegalStateException("workspace read disabled by suite.workspace.allow-read=false");
        }
    }

    void ensureWrite() {
        ensureEnabled();
        if (!properties.effectiveAllowWrite()) {
            throw new IllegalStateException("workspace write disabled by suite.workspace.allow-write=false");
        }
    }

    void ensureDelete() {
        ensureEnabled();
        if (!properties.effectiveAllowDelete()) {
            throw new IllegalStateException("workspace delete disabled by suite.workspace.allow-delete=false");
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("workspace disabled");
        }
    }
}
