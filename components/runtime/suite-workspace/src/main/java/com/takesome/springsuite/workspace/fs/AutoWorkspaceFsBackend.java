package com.takesome.springsuite.workspace.fs;

import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.workspace.WorkspaceEntry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AutoWorkspaceFsBackend implements WorkspaceFsBackend {
    private final WorkspaceFsBackend primary;
    private final WorkspaceFsBackend fallback;
    private final OperatorLogService logService;
    private final AtomicBoolean warned = new AtomicBoolean(false);

    public AutoWorkspaceFsBackend(WorkspaceFsBackend primary, WorkspaceFsBackend fallback, OperatorLogService logService) {
        this.primary = primary;
        this.fallback = fallback;
        this.logService = logService;
    }

    @Override
    public WorkspaceFsBackendKind kind() {
        return primary.isAvailable() ? primary.kind() : fallback.kind();
    }

    @Override
    public boolean isAvailable() {
        return primary.isAvailable() || fallback.isAvailable();
    }

    @Override
    public List<WorkspaceEntry> list(Path target, int maxEntries, WorkspacePathPolicy pathPolicy) {
        try {
            if (primary.isAvailable()) {
                return primary.list(target, maxEntries, pathPolicy);
            }
        } catch (RuntimeException ex) {
            warnFallback(ex);
        }
        return fallback.list(target, maxEntries, pathPolicy);
    }

    @Override
    public List<WorkspaceEntry> tree(Path target, int maxDepth, int maxEntries, WorkspacePathPolicy pathPolicy) {
        try {
            if (primary.isAvailable()) {
                return primary.tree(target, maxDepth, maxEntries, pathPolicy);
            }
        } catch (RuntimeException ex) {
            warnFallback(ex);
        }
        return fallback.tree(target, maxDepth, maxEntries, pathPolicy);
    }

    @Override
    public byte[] readAllBytes(Path target) {
        try {
            if (primary.isAvailable()) {
                return primary.readAllBytes(target);
            }
        } catch (RuntimeException ex) {
            warnFallback(ex);
        }
        return fallback.readAllBytes(target);
    }

    @Override
    public void close() {
        primary.close();
        fallback.close();
    }

    private void warnFallback(RuntimeException ex) {
        if (warned.compareAndSet(false, true)) {
            logService.append(OperatorLogLevel.WARN, "workspace", "native fs backend failed; falling back to jvm", Map.of(
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ));
        }
    }
}
