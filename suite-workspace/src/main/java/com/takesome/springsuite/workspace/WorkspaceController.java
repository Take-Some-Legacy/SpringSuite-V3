package com.takesome.springsuite.workspace;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkspaceController {
    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping("/api/workspace")
    public SuiteApiResponse<WorkspaceSummary> summary() {
        return SuiteApiResponse.ok(workspaceService.summary());
    }

    @GetMapping("/api/workspace/list")
    public SuiteApiResponse<WorkspaceListResult> list(
            @RequestParam(name = "path", required = false, defaultValue = ".") String path,
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit
    ) {
        try {
            return SuiteApiResponse.ok(workspaceService.list(path, limit));
        } catch (Exception ex) {
            return SuiteApiResponse.failed("workspace_list_failed", ex.getMessage(), null);
        }
    }

    @GetMapping("/api/workspace/tree")
    public SuiteApiResponse<WorkspaceListResult> tree(
            @RequestParam(name = "path", required = false, defaultValue = ".") String path,
            @RequestParam(name = "depth", required = false, defaultValue = "3") int depth,
            @RequestParam(name = "limit", required = false, defaultValue = "500") int limit
    ) {
        try {
            return SuiteApiResponse.ok(workspaceService.tree(path, depth, limit));
        } catch (Exception ex) {
            return SuiteApiResponse.failed("workspace_tree_failed", ex.getMessage(), null);
        }
    }

    @GetMapping("/api/workspace/read")
    public SuiteApiResponse<WorkspaceReadResult> read(
            @RequestParam(name = "path") String path,
            @RequestParam(name = "offset", required = false, defaultValue = "0") int offset,
            @RequestParam(name = "maxBytes", required = false, defaultValue = "65536") int maxBytes
    ) {
        try {
            return SuiteApiResponse.ok(workspaceService.read(path, offset, maxBytes));
        } catch (Exception ex) {
            return SuiteApiResponse.failed("workspace_read_failed", ex.getMessage(), null);
        }
    }

    @GetMapping("/api/workspace/search")
    public SuiteApiResponse<WorkspaceSearchResult> search(
            @RequestParam(name = "q") String query,
            @RequestParam(name = "path", required = false, defaultValue = ".") String path,
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit,
            @RequestParam(name = "regex", required = false, defaultValue = "false") boolean regex,
            @RequestParam(name = "caseSensitive", required = false, defaultValue = "false") boolean caseSensitive
    ) {
        try {
            return SuiteApiResponse.ok(workspaceService.search(query, path, limit, regex, caseSensitive));
        } catch (Exception ex) {
            return SuiteApiResponse.failed("workspace_search_failed", ex.getMessage(), null);
        }
    }

    @PostMapping("/api/workspace/write")
    public SuiteApiResponse<WorkspaceWriteResult> write(@RequestBody WorkspaceWriteRequest request) {
        try {
            WorkspaceWriteResult result = workspaceService.write(request);
            return result.ok() ? SuiteApiResponse.ok(result) : SuiteApiResponse.failed("workspace_write_rejected", result.message(), result);
        } catch (Exception ex) {
            return SuiteApiResponse.failed("workspace_write_failed", ex.getMessage(), null);
        }
    }

    @PostMapping("/api/workspace/mkdir")
    public SuiteApiResponse<WorkspaceMutationResult> mkdir(
            @RequestParam(name = "path") String path,
            @RequestParam(name = "dryRun", required = false, defaultValue = "false") boolean dryRun
    ) {
        try {
            return SuiteApiResponse.ok(workspaceService.mkdir(path, dryRun));
        } catch (Exception ex) {
            return SuiteApiResponse.failed("workspace_mkdir_failed", ex.getMessage(), null);
        }
    }

    @PostMapping("/api/workspace/delete")
    public SuiteApiResponse<WorkspaceMutationResult> delete(@RequestBody WorkspaceDeleteRequest request) {
        try {
            return SuiteApiResponse.ok(workspaceService.delete(request));
        } catch (Exception ex) {
            return SuiteApiResponse.failed("workspace_delete_failed", ex.getMessage(), null);
        }
    }
}
