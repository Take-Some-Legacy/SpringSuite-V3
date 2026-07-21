package com.takesome.springsuite.app;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandRegistry;
import com.takesome.springsuite.core.api.SuiteApiResponse;
import com.takesome.springsuite.toolbelt.ToolIndexEntry;
import com.takesome.springsuite.toolbelt.ToolbeltService;
import com.takesome.springsuite.toolbelt.ToolbeltSummary;
import com.takesome.springsuite.workspace.WorkspaceSummary;
import com.takesome.springsuite.workspace.WorkspaceService;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ControlPanelController {
    private final Instant startedAt = Instant.now();
    private final SuiteBuildInfo suiteBuildInfo;
    private final WorkspaceService workspaceService;
    private final ToolbeltService toolbeltService;
    private final CommandRegistry commandRegistry;

    public ControlPanelController(
            SuiteBuildInfo suiteBuildInfo,
            WorkspaceService workspaceService,
            ToolbeltService toolbeltService,
            CommandRegistry commandRegistry
    ) {
        this.suiteBuildInfo = suiteBuildInfo;
        this.workspaceService = workspaceService;
        this.toolbeltService = toolbeltService;
        this.commandRegistry = commandRegistry;
    }

    @GetMapping("/api/control-panel")
    public SuiteApiResponse<ControlPanelSnapshot> snapshot() {
        Instant started = Instant.now();
        WorkspaceSummary workspace = workspaceService.summary();
        ToolbeltSummary toolbelt = toolbeltService.summary();
        List<CommandLite> commands = commandRegistry.descriptors().stream()
                .map(CommandLite::from)
                .toList();
        List<ToolLite> tools = toolbeltService.index().stream()
                .limit(24)
                .map(ToolLite::from)
                .toList();
        Instant generatedAt = Instant.now();
        return SuiteApiResponse.ok(new ControlPanelSnapshot(
                "spring-suite.control-panel.v1",
                generatedAt,
                Duration.between(started, generatedAt).toMillis(),
                system(),
                workspace,
                toolbelt,
                tools,
                commands,
                endpoints()
        ));
    }

    private SystemPanel system() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("java", Runtime.version().toString());
        components.put("pid", ManagementFactory.getRuntimeMXBean().getPid());
        components.put("name", suiteBuildInfo.name());
        components.put("version", suiteBuildInfo.version());
        components.put("build", suiteBuildInfo.build());
        components.put("buildTime", suiteBuildInfo.time());
        components.put("gitCommit", suiteBuildInfo.commit());
        components.put("gitBranch", suiteBuildInfo.branch());
        components.put("gitDirty", suiteBuildInfo.dirty());
        components.put("projectRoot", System.getProperty("suite.project.root", ""));
        components.put("workingDirectory", System.getProperty("suite.working.directory", ""));
        components.put("launchDirectory", System.getProperty("suite.launch.dir", ""));
        components.put("userDir", System.getProperty("user.dir", ""));
        components.put("modulesEnabled", System.getProperty("suite.modules.enabled", ""));
        components.put("modulesDir", System.getProperty("suite.modules.dir", ""));
        components.put("modulesCount", System.getProperty("suite.modules.count", "0"));
        components.put("modulesDiscoveredCount", System.getProperty("suite.modules.discovered.count", "0"));
        components.put("modulesBlockedCount", System.getProperty("suite.modules.blocked.count", "0"));
        components.put("modulesTrustMode", System.getProperty("suite.modules.trust.mode", ""));
        components.put("configDir", System.getProperty("suite.config.dir", ""));
        components.put("logsPath", System.getProperty("suite.logs.path", ""));
        return new SystemPanel("spring-suite", "READY", startedAt, components);
    }

    private List<ApiEndpoint> endpoints() {
        return List.of(
                new ApiEndpoint("controlPanel", "GET", "/api/control-panel", "Compact panel snapshot", "READ_ONLY"),
                new ApiEndpoint("system", "GET", "/api/system/status", "Full system status", "READ_ONLY"),
                new ApiEndpoint("manifest", "GET", "/api/agent/manifest", "Full agent manifest", "READ_ONLY_HEAVY"),
                new ApiEndpoint("workspace", "GET", "/api/workspace", "Workspace contract", "READ_ONLY"),
                new ApiEndpoint("toolbelt", "GET", "/api/toolbelt", "Toolbelt summary", "READ_ONLY"),
                new ApiEndpoint("toolIndex", "GET", "/api/toolbelt/index", "Full tool index with search terms", "READ_ONLY_HEAVY"),
                new ApiEndpoint("commands", "GET", "/api/commands", "Command registry", "READ_ONLY"),
                new ApiEndpoint("requestJournal", "GET", "/api/admin/requests", "SQL-backed request journal search", "READ_ONLY_HEAVY"),
                new ApiEndpoint("requestJournalStats", "GET", "/api/admin/requests/stats", "Request journal statistics", "READ_ONLY"),
                new ApiEndpoint("requestJournalStream", "GET", "/api/admin/requests/stream", "Live request journal SSE notifications", "READ_ONLY_STREAM")
        );
    }

    public record ControlPanelSnapshot(
            String schema,
            Instant generatedAt,
            long durationMs,
            SystemPanel system,
            WorkspaceSummary workspace,
            ToolbeltSummary toolbelt,
            List<ToolLite> tools,
            List<CommandLite> commands,
            List<ApiEndpoint> endpoints
    ) {
        public ControlPanelSnapshot {
            tools = tools == null ? List.of() : List.copyOf(tools);
            commands = commands == null ? List.of() : List.copyOf(commands);
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        }
    }

    public record SystemPanel(
            String application,
            String status,
            Instant startedAt,
            Map<String, Object> components
    ) {
        public SystemPanel {
            components = components == null ? Map.of() : Map.copyOf(components);
        }
    }

    public record ToolLite(
            String id,
            String publicName,
            String source,
            String kind,
            boolean available
    ) {
        static ToolLite from(ToolIndexEntry tool) {
            return new ToolLite(
                    tool.id(),
                    tool.publicName(),
                    tool.source(),
                    tool.kind(),
                    tool.available()
            );
        }
    }

    public record CommandLite(
            String name,
            String category,
            String summary,
            String usage,
            String riskLevel
    ) {
        static CommandLite from(CommandDescriptor command) {
            return new CommandLite(
                    command.name(),
                    command.category(),
                    command.summary(),
                    command.usage(),
                    String.valueOf(command.riskLevel())
            );
        }
    }

    public record ApiEndpoint(
            String id,
            String method,
            String endpoint,
            String summary,
            String risk
    ) {
    }
}
