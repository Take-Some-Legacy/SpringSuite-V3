package com.takesome.springsuite.workspace;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

final class WorkspaceBackupPlanner {
    Path backupPath(Path target) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(Instant.now());
        return target.resolveSibling(target.getFileName() + ".bak-" + timestamp);
    }
}
