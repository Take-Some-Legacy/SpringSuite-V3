package com.takesome.springsuite.workspace;

import com.takesome.springsuite.core.mode.SuiteOperatorMode;
import com.takesome.springsuite.workspace.fs.WorkspacePathPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class WorkspaceTextFilePolicy {
    private static final int TEXT_SNIFF_BYTES = 4096;
    private static final long SEARCH_EXTENSIONLESS_SNIFF_MAX_BYTES = 1024L * 1024L;

    private final WorkspaceProperties properties;
    private final WorkspacePathPolicy pathPolicy;
    private final List<String> textExtensions;

    WorkspaceTextFilePolicy(WorkspaceProperties properties, WorkspacePathPolicy pathPolicy) {
        this.properties = properties;
        this.pathPolicy = pathPolicy;
        this.textExtensions = properties.getTextExtensions().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    void ensureTextFile(Path target) {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("not a regular file: " + pathPolicy.displayPath(target));
        }
        if (SuiteOperatorMode.isElevated()) {
            return;
        }
        try {
            if (exceedsConfiguredMax(Files.size(target))) {
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
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (SuiteOperatorMode.isElevated()) {
            return true;
        }
        String file = lowerFileName(target);
        if (matchesConfiguredTextName(file)) {
            return true;
        }
        try {
            long size = Files.size(target);
            return !exceedsConfiguredMax(size) && sniffText(target);
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Search is intentionally stricter than direct read. Known source/text extensions are accepted
     * without opening the file; only small extensionless files (Dockerfile, Makefile, .env, etc.)
     * are sniffed. This prevents repository search from touching every binary asset just to decide
     * whether it might contain text.
     */
    boolean isSearchableText(Path target, long knownSize) {
        if (exceedsConfiguredMax(knownSize)) {
            return false;
        }
        String file = lowerFileName(target);
        if (matchesConfiguredTextName(file)) {
            return true;
        }
        if (!isExtensionlessCandidate(file) || knownSize > SEARCH_EXTENSIONLESS_SNIFF_MAX_BYTES) {
            return false;
        }
        try {
            return sniffText(target);
        } catch (IOException ex) {
            return false;
        }
    }

    private boolean exceedsConfiguredMax(long size) {
        return properties.getMaxFileSizeBytes() > 0 && size > properties.getMaxFileSizeBytes();
    }

    private boolean matchesConfiguredTextName(String file) {
        for (String extension : textExtensions) {
            if (file.equals(extension) || file.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExtensionlessCandidate(String file) {
        if (file.isBlank()) {
            return false;
        }
        int lastDot = file.lastIndexOf('.');
        return lastDot <= 0;
    }

    private String lowerFileName(Path target) {
        return target.getFileName() == null ? "" : target.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private boolean sniffText(Path target) throws IOException {
        byte[] sample = readPrefix(target, TEXT_SNIFF_BYTES);
        for (byte b : sample) {
            if (b == 0) {
                return false;
            }
        }
        new String(sample, StandardCharsets.UTF_8);
        return true;
    }

    private byte[] readPrefix(Path target, int limit) throws IOException {
        if (limit <= 0) {
            return new byte[0];
        }
        byte[] buffer = new byte[limit];
        int offset = 0;
        try (InputStream input = Files.newInputStream(target)) {
            while (offset < limit) {
                int read = input.read(buffer, offset, limit - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        return offset == buffer.length ? buffer : Arrays.copyOf(buffer, offset);
    }
}
