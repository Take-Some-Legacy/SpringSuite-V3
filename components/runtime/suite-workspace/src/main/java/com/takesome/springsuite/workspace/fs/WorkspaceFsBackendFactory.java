package com.takesome.springsuite.workspace.fs;

import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.util.Locale;
import java.util.Map;

public final class WorkspaceFsBackendFactory {
    private WorkspaceFsBackendFactory() {
    }

    public static WorkspaceFsBackend create(SuiteFsProperties properties, WorkspacePathPolicy pathPolicy, OperatorLogService logService) {
        String mode = properties.getBackend() == null ? "auto" : properties.getBackend().trim().toLowerCase(Locale.ROOT);
        JvmWorkspaceFsBackend jvm = new JvmWorkspaceFsBackend();

        if (mode.equals("jvm")) {
            logService.append(OperatorLogLevel.INFO, "workspace", "fs backend selected", Map.of("backend", "jvm"));
            return jvm;
        }

        if (mode.equals("go")) {
            GoWorkspaceFsBackend go = new GoWorkspaceFsBackend(properties, pathPolicy, logService);
            go.start();
            if (!go.isAvailable()) {
                throw new IllegalStateException("suite.fs.backend=go but suite-fs-worker is unavailable");
            }
            logService.append(OperatorLogLevel.INFO, "workspace", "fs backend selected", Map.of("backend", "go"));
            return go;
        }

        if (!mode.equals("auto")) {
            logService.append(OperatorLogLevel.WARN, "workspace", "unknown fs backend; using auto", Map.of("backend", mode));
        }

        try {
            GoWorkspaceFsBackend go = new GoWorkspaceFsBackend(properties, pathPolicy, logService);
            go.start();
            if (go.isAvailable()) {
                logService.append(OperatorLogLevel.INFO, "workspace", "fs backend selected", Map.of("backend", "auto/go"));
                return new AutoWorkspaceFsBackend(go, jvm, logService);
            }
        } catch (RuntimeException ex) {
            logService.append(OperatorLogLevel.WARN, "workspace", "go fs backend startup failed", Map.of(
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ));
        }

        logService.append(OperatorLogLevel.WARN, "workspace", "falling back to jvm fs backend", Map.of("backend", "jvm"));
        return jvm;
    }
}
