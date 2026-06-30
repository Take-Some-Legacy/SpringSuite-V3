package com.takesome.springsuite.workspace;

import com.takesome.springsuite.workspace.fs.WorkspacePathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class WorkspaceTextFilePolicy {
    private final WorkspaceProperties properties;
    private final WorkspacePathPolicy pathPolicy;

    WorkspaceTextFilePolicy(WorkspaceProperties properties, WorkspacePathPolicy pathPolicy) {
        this.properties = properties;
        this.pathPolicy = pathPolicy;
    }

    void ensureTextFile(Path target) {
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("not a regular file: " + pathPolicy.displayPath(target));
        }
        try {
            if (Files.size(target) > properties.getMaxFileSizeBytes()) {
                throw new IllegalArgumentException("file exceeds suite.workspace.max-file-size-bytes: " + pathPolicy.displayPath(target));
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to stat file: " + ex.getMessage(), ex);
        }
        if (!isProbablyText(target)) {
            throw new IllegalArgumentException("file is not in configured text extensions and does not look UTF-8 text: " + pathPolicy.displayPath(target));
        }
    }

    boolean isProbablyText(Path target) {
        String file = target.getFileName() == null ? "" : target.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : properties.getTextExtensions()) {
            String normalized = ext.toLowerCase(Locale.ROOT);
            if (file.equals(normalized) || file.endsWith(normalized)) {
                return true;
            }
        }
        try {
            if (!Files.isRegularFile(target) || Files.size(target) > properties.getMaxFileSizeBytes()) {
                return false;
            }
            byte[] sample = readPrefix(target, 4096);
            for (byte b : sample) {
                if (b == 0) {
                    return false;
                }
            }
            new String(sample, StandardCharsets.UTF_8);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private byte[] readPrefix(Path target, int limit) throws IOException {
        byte[] all = Files.readAllBytes(target);
        return all.length <= limit ? all : java.util.Arrays.copyOfRange(all, 0, limit);
    }
}
