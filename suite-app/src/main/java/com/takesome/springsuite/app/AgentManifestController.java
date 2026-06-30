package com.takesome.springsuite.app;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandRegistry;
import com.takesome.springsuite.core.api.SuiteApiResponse;
import com.takesome.springsuite.toolbelt.ToolIndexEntry;
import com.takesome.springsuite.toolbelt.ToolbeltService;
import com.takesome.springsuite.workspace.WorkspaceOperationDescriptor;
import com.takesome.springsuite.workspace.WorkspaceService;
import com.takesome.springsuite.workspace.WorkspaceSummary;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentManifestController {
    private final CommandRegistry commandRegistry;
    private final ToolbeltService toolbeltService;
    private final WorkspaceService workspaceService;

    public AgentManifestController(CommandRegistry commandRegistry, ToolbeltService toolbeltService, WorkspaceService workspaceService) {
        this.commandRegistry = commandRegistry;
        this.toolbeltService = toolbeltService;
        this.workspaceService = workspaceService;
    }

    @GetMapping("/api/agent/manifest")
    public SuiteApiResponse<AgentManifest> agentManifest() {
        return SuiteApiResponse.ok(manifest());
    }

    @GetMapping("/api/help")
    public SuiteApiResponse<AgentManifest> help() {
        return SuiteApiResponse.ok("SpringSuite command/tool/workspace manifest", manifest());
    }

    @GetMapping(value = "/api/help.md", produces = MediaType.TEXT_PLAIN_VALUE)
    public String helpMarkdown() {
        AgentManifest manifest = manifest();
        StringBuilder out = new StringBuilder(16_384);
        out.append("# SpringSuite Agent & Human Help\n\n");
        out.append("Schema: `").append(manifest.schema()).append("`\n\n");
        out.append("## Workspace file access\n\n");
        out.append("- Enabled: `").append(manifest.workspace().enabled()).append("`\n");
        out.append("- Read: `").append(manifest.workspace().allowRead()).append("`\n");
        out.append("- Write: `").append(manifest.workspace().allowWrite()).append("`\n");
        out.append("- Delete: `").append(manifest.workspace().allowDelete()).append("`\n");
        out.append("- Roots:\n");
        for (String root : manifest.workspace().roots()) {
            out.append("  - `").append(root).append("`\n");
        }
        out.append("\n### Workspace operations\n\n");
        for (WorkspaceOperationDescriptor operation : manifest.workspace().operations()) {
            out.append("- `").append(operation.command()).append("` — ").append(operation.summary()).append(" ")
                    .append(operation.method()).append(" ").append(operation.endpoint()).append("\n");
        }
        out.append("\n## Console commands\n\n");
        for (CommandDescriptor command : manifest.commands()) {
            out.append("### `").append(command.name()).append("`\n\n");
            out.append("- Category: `").append(command.category()).append("`\n");
            out.append("- Risk: `").append(command.riskLevel()).append("`\n");
            out.append("- Usage: `").append(command.usage()).append("`\n");
            out.append("- Summary: ").append(command.summary()).append("\n");
            out.append("- Description: ").append(command.description()).append("\n\n");
        }
        out.append("## Toolbelt tools\n\n");
        for (ToolIndexEntry tool : manifest.tools()) {
            out.append("- `").append(tool.id()).append("` / `").append(tool.publicName()).append("` — ")
                    .append(tool.source()).append("/").append(tool.kind()).append(" available=").append(tool.available()).append("\n");
        }
        return out.toString();
    }

    private AgentManifest manifest() {
        return new AgentManifest(
                "spring-suite.agent.manifest.v1",
                Instant.now(),
                "Agents may browse/read/search/write files only through configured workspace roots and gates. Humans can inspect the same contract through /api/help.md.",
                workspaceService.summary(),
                commandRegistry.descriptors(),
                toolbeltService.index()
        );
    }

    public record AgentManifest(
            String schema,
            Instant generatedAt,
            String description,
            WorkspaceSummary workspace,
            List<CommandDescriptor> commands,
            List<ToolIndexEntry> tools
    ) {
        public AgentManifest {
            commands = commands == null ? List.of() : List.copyOf(commands);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }
}
