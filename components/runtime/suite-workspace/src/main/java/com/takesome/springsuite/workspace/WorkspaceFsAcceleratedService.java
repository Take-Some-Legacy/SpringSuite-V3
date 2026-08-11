package com.takesome.springsuite.workspace;

import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.workspace.fs.SuiteFsProperties;
import com.takesome.springsuite.workspace.fs.WorkspaceFsBackend;
import com.takesome.springsuite.workspace.fs.WorkspaceFsBackendFactory;
import com.takesome.springsuite.workspace.fs.WorkspacePathPolicy;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class WorkspaceFsAcceleratedService extends WorkspaceService {
    private final WorkspaceProperties properties;
    private final WorkspacePathPolicy pathPolicy;
    private final WorkspaceAccessGuard accessGuard;
    private final WorkspaceTextFilePolicy textFilePolicy;
    private final WorkspaceFsBackend fsBackend;

    public WorkspaceFsAcceleratedService(
            WorkspaceProperties properties,
            SuiteFsProperties fsProperties,
            OperatorLogService logService,
            WorkspacePathPolicy pathPolicy,
            RepositoryDescriptorService repositoryDescriptorService
    ) {
        super(properties, logService, pathPolicy, repositoryDescriptorService);
        this.properties = properties;
        this.pathPolicy = pathPolicy;
        this.accessGuard = new WorkspaceAccessGuard(properties);
        this.textFilePolicy = new WorkspaceTextFilePolicy(properties, pathPolicy);
        this.fsBackend = WorkspaceFsBackendFactory.create(fsProperties, pathPolicy, logService);
    }

    @PreDestroy
    public void closeFsBackend() {
        fsBackend.close();
    }

    @Override
    public WorkspaceListResult list(String path, int limit) {
        accessGuard.ensureRead();
        Path target = pathPolicy.resolveSafe(path);
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("not a directory: " + pathPolicy.displayPath(target));
        }
        int safeLimit = effectiveLimit(limit, properties.getMaxTreeItems());
        List<WorkspaceEntry> raw = fsBackend.list(target, safeLimit + 1, pathPolicy);
        boolean truncated = raw.size() > safeLimit;
        List<WorkspaceEntry> entries = truncated ? raw.subList(0, safeLimit) : raw;
        return new WorkspaceListResult(pathPolicy.displayPath(target), truncated, entries.size(), entries);
    }

    @Override
    public WorkspaceListResult tree(String path, int depth, int limit) {
        accessGuard.ensureRead();
        Path target = pathPolicy.resolveSafe(path);
        int safeDepth = depth <= 0 ? Integer.MAX_VALUE : depth;
        int safeLimit = effectiveLimit(limit, properties.getMaxTreeItems());
        List<WorkspaceEntry> raw = fsBackend.tree(target, safeDepth, safeLimit + 1, pathPolicy);
        boolean truncated = raw.size() > safeLimit;
        List<WorkspaceEntry> entries = truncated ? raw.subList(0, safeLimit) : raw;
        return new WorkspaceListResult(pathPolicy.displayPath(target), truncated, entries.size(), entries);
    }

    @Override
    public WorkspaceReadResult read(String path, int offset, int maxBytes) {
        accessGuard.ensureRead();
        Path target = pathPolicy.resolveSafe(path);
        textFilePolicy.ensureTextFile(target);
        int safeOffset = Math.max(0, offset);
        int configuredMax = effectiveLimit(maxBytes, properties.getMaxReadBytes());
        byte[] all = fsBackend.readAllBytes(target);
        int safeMax = Math.min(configuredMax, all.length);
        int start = Math.min(safeOffset, all.length);
        int end = (int) Math.min(all.length, (long) start + safeMax);
        byte[] slice = java.util.Arrays.copyOfRange(all, start, end);
        boolean truncated = end < all.length;
        return new WorkspaceReadResult(
                pathPolicy.displayPath(target),
                all.length,
                start,
                slice.length,
                truncated,
                WorkspaceDigest.sha256(all),
                new String(slice, StandardCharsets.UTF_8)
        );
    }

    private static int effectiveLimit(int requested, int configured) {
        if (requested > 0) {
            return configured > 0 ? Math.min(requested, configured) : requested;
        }
        return configured > 0 ? configured : Integer.MAX_VALUE - 1;
    }

}
