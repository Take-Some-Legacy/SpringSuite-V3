package com.takesome.springsuite.workspace;

import com.takesome.springsuite.core.mode.SuiteOperatorMode;
final class WorkspaceAccessGuard {
    private final WorkspaceProperties properties;

    WorkspaceAccessGuard(WorkspaceProperties properties) {
        this.properties = properties;
    }

    void ensureRead() {
        if (SuiteOperatorMode.isElevated()) {
            return;
        }
        ensureEnabled();
        if (!properties.effectiveAllowRead()) {
            throw new IllegalStateException("workspace read disabled by suite.workspace.allow-read=false");
        }
    }

    void ensureWrite() {
        if (SuiteOperatorMode.isElevated()) {
            return;
        }
        ensureEnabled();
        if (!properties.effectiveAllowWrite()) {
            throw new IllegalStateException("workspace write disabled by suite.workspace.allow-write=false");
        }
    }

    void ensureDelete() {
        if (SuiteOperatorMode.isElevated()) {
            return;
        }
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
