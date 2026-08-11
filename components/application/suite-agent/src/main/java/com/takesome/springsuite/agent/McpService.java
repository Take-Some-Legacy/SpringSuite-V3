package com.takesome.springsuite.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandRegistry;
import com.takesome.springsuite.desktop.DesktopFormRelay;
import com.takesome.springsuite.agent.audit.AgentAuditService;
import com.takesome.springsuite.toolbelt.ToolDescriptor;
import com.takesome.springsuite.toolbelt.ToolIndexEntry;
import com.takesome.springsuite.toolbelt.ToolRunRequest;
import com.takesome.springsuite.toolbelt.ToolRunResult;
import com.takesome.springsuite.toolbelt.ToolbeltService;
import com.takesome.springsuite.workspace.WorkspaceDeleteRequest;
import com.takesome.springsuite.workspace.WorkspaceService;
import com.takesome.springsuite.workspace.WorkspaceWriteRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class McpService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SuiteMcpProperties properties;
    private final SuiteAuthService authService;
    private final OAuthScopeRegistry scopes;
    private final WorkspaceService workspaceService;
    private final ToolbeltService toolbeltService;
    private final CommandRegistry commandRegistry;
    private final DesktopFormRelay desktopFormRelay;
    private final BasicKnowledgeStore basicKnowledgeStore;
    private final ObjectMapper objectMapper;
    private final AgentAuditService audit;

    public McpService(
            SuiteMcpProperties properties,
            SuiteAuthService authService,
            OAuthScopeRegistry scopes,
            WorkspaceService workspaceService,
            ToolbeltService toolbeltService,
            CommandRegistry commandRegistry,
            DesktopFormRelay desktopFormRelay,
            BasicKnowledgeStore basicKnowledgeStore,
            ObjectMapper objectMapper,
            AgentAuditService audit
    ) {
        this.properties = properties;
        this.authService = authService;
        this.scopes = scopes;
        this.workspaceService = workspaceService;
        this.toolbeltService = toolbeltService;
        this.commandRegistry = commandRegistry;
        this.desktopFormRelay = desktopFormRelay;
        this.basicKnowledgeStore = basicKnowledgeStore;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    public Map<String, Object> discovery(String baseUrl) {
        return orderedMap(
                "ok", true,
                "name", properties.getServerName(),
                "version", "0.1.12",
                "title", properties.getServerTitle(),
                "description", properties.getDescription(),
                "endpoint", properties.getEndpoint(),
                "protocolVersion", properties.getProtocolVersion(),
                "serverInfo", serverInfo(),
                "authorization", orderedMap(
                        "protectedResource", baseUrl.replaceAll("/$", "") + "/.well-known/oauth-protected-resource" + properties.getEndpoint(),
                        "authorizationServer", baseUrl.replaceAll("/$", "") + "/.well-known/oauth-authorization-server",
                        "tokenEndpoint", baseUrl.replaceAll("/$", "") + "/oauth/token",
                        "registrationEndpoint", baseUrl.replaceAll("/$", "") + "/oauth/register"
                ),
                "capabilities", capabilities(),
                "methods", List.of("initialize", "notifications/initialized", "ping", "tools/list", "tools/call", "resources/list", "resources/templates/list", "prompts/list"),
                "toolCount", tools().size(),
                "agentContext", orderedMap(
                        "schema", "spring-suite.agent_context.v1",
                        "workspace", workspaceService.summary(),
                        "auth", authService.status(),
                        "tools", orderedMap("count", tools().size(), "listMethod", "tools/list", "callMethod", "tools/call")
                )
        );
    }

    public Map<String, Object> handle(Map<String, Object> request, AuthContext auth) {
        Object id = request.get("id");
        String method = String.valueOf(request.getOrDefault("method", ""));
        try {
            Object result = switch (method) {
                case "initialize" -> initialize();
                case "notifications/initialized" -> Map.of("ok", true);
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> callTool(params(request), auth);
                case "resources/list", "resources/templates/list" -> Map.of("resources", List.of(), "resourceTemplates", List.of());
                case "prompts/list" -> Map.of("prompts", List.of());
                default -> throw new McpException(-32601, "Method not found: " + method);
            };
            return orderedMap("jsonrpc", "2.0", "id", id, "result", result);
        } catch (McpException ex) {
            return orderedMap("jsonrpc", "2.0", "id", id, "error", orderedMap("code", ex.code(), "message", ex.getMessage()));
        } catch (Exception ex) {
            return orderedMap("jsonrpc", "2.0", "id", id, "error", orderedMap("code", -32000, "message", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    private Map<String, Object> initialize() {
        return orderedMap(
                "protocolVersion", properties.getProtocolVersion(),
                "serverInfo", serverInfo(),
                "capabilities", capabilities(),
                "instructions", "SpringSuite Agent Bridge. Use tools/list, then tools/call. File access is bounded by suite.workspace roots and OAuth scopes."
        );
    }

    private Map<String, Object> serverInfo() {
        return orderedMap(
                "name", properties.getServerName(),
                "title", properties.getServerTitle(),
                "version", "0.1.12",
                "description", properties.getDescription()
        );
    }

    private Map<String, Object> capabilities() {
        return orderedMap(
                "tools", Map.of("listChanged", false),
                "resources", Map.of("subscribe", false, "listChanged", false),
                "prompts", Map.of("listChanged", false)
        );
    }

    public List<Map<String, Object>> tools() {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        out.add(tool("workspace.list", "List workspace directory entries.", schema(orderedMap("path", str("Path to list. Defaults to ."), "limit", integer("Maximum entries.")), List.of()), true));
        out.add(tool("workspace.tree", "List a bounded recursive workspace tree.", schema(orderedMap("path", str("Root path."), "depth", integer("Depth."), "limit", integer("Maximum entries.")), List.of()), true));
        out.add(tool("workspace.read", "Read a bounded UTF-8 text file with SHA-256.", schema(orderedMap("path", str("File path."), "offset", integer("Byte offset."), "maxBytes", integer("Maximum bytes.")), List.of("path")), true));
        out.add(tool("workspace.search", "Search text files under a workspace path.", schema(orderedMap("query", str("Search query."), "path", str("Root path."), "limit", integer("Maximum matches."), "regex", bool("Treat query as regex."), "caseSensitive", bool("Case-sensitive search.")), List.of("query")), true));
        out.add(tool("workspace.write", "Create or replace a workspace text file. Uses backups and optional expectedSha256.", schema(orderedMap("path", str("File path."), "content", str("UTF-8 content."), "createParents", bool("Create parent directories."), "dryRun", bool("Validate only."), "expectedSha256", str("Optional optimistic lock.")), List.of("path", "content")), false));
        out.add(tool("workspace.mkdir", "Create a workspace directory.", schema(orderedMap("path", str("Directory path."), "dryRun", bool("Validate only.")), List.of("path")), false));
        out.add(tool("workspace.delete", "Delete a workspace path. Requires delete gate and admin scope.", schema(orderedMap("path", str("Path."), "recursive", bool("Recursive directory delete."), "dryRun", bool("Validate only.")), List.of("path")), false));
        out.add(tool("toolbelt.search", "Search discovered Suite tools.", schema(orderedMap("query", str("Tool query."), "limit", integer("Maximum tools.")), List.of()), true));
        out.add(tool("toolbelt.inventory", "Return toolbelt inventory and index summary.", schema(Map.of(), List.of()), true));
        out.add(tool("basicKnowledge.remember", "Save or update a global BasicKnowledge fact shared across repositories.", schema(orderedMap("key", str("Stable key."), "value", str("Value to remember."), "tags", array("Tags."), "source", str("Source label.")), List.of("key", "value")), false));
        out.add(tool("basicKnowledge.search", "Search global BasicKnowledge facts.", schema(orderedMap("query", str("Search query.")), List.of()), true));
        out.add(tool("basicKnowledge.list", "List all global BasicKnowledge facts.", schema(Map.of(), List.of()), true));
        out.add(tool("basicKnowledge.dump", "Dump the BasicKnowledge database metadata and items.", schema(Map.of(), List.of()), true));
        out.add(tool("toolbelt.run", "Run a discovered toolbelt tool by id/name when execution is enabled.", schema(orderedMap("toolId", str("Tool id/name/public name."), "args", array("Arguments."), "cwd", str("Working directory."), "stdin", str("Optional stdin."), "timeoutSec", integer("Timeout seconds."), "dryRun", bool("Dry-run command construction.")), List.of("toolId")), false));
        out.add(tool("command.execute", "Execute a SpringSuite console command line.", schema(orderedMap("line", str("Command line.")), List.of("line")), false));
        out.add(tool("fn.list", "List FN operator buttons.", schema(Map.of(), List.of()), true));
        out.add(tool("fn.trigger", "Trigger one FN operator button.", schema(orderedMap("fn", str("FN button code."), "target", str("Target."), "maxWidth", integer("Max width.")), List.of("fn")), false));
        out.add(tool("desktop.screenshot.send", "Explicit operator image handoff.", schema(orderedMap("target", str("Target."), "maxWidth", integer("Max width.")), List.of()), false));
        out.add(tool("desktop.form.relay.current",
                "Return the active form waiting for a ChatGPT 5.6 draft. Existing values and secrets are never returned.",
                schema(Map.of(), List.of()), true));
        out.add(tool("desktop.form.relay.status",
                "Return the current ChatGPT form relay status without field contents.",
                schema(orderedMap("relayId", str("Optional relay id.")), List.of()), true));
        out.add(tool("desktop.form.relay.submit",
                "Submit operator-reviewed draft values for the active form. SpringSuite validates them and does not write anything until the operator presses Fill.",
                schema(orderedMap(
                        "relayId", str("Relay id returned by desktop.form.relay.current."),
                        "fields", arrayOf("Draft field values.", schema(orderedMap(
                "fieldId", str("Exact fieldId returned by desktop.form.relay.current."),
                "value", str("Draft value. Do not submit secrets, authentication data, payment data or inferred personal facts."),
                "reason", str("Brief reason for this value."),
                "confidence", orderedMap("type", "number", "description", "Confidence from 0 to 1.")
        ), List.of("fieldId", "value"))),
                        "summary", str("Brief summary shown in the overlay.")
                ), List.of("relayId", "fields")), false));
        for (ToolIndexEntry entry : toolbeltService.index()) {
            out.add(tool(entry.publicName(), "Descriptor tool: " + entry.name() + " [" + entry.source() + "/" + entry.kind() + "]", schema(orderedMap("args", array("Arguments."), "cwd", str("Working directory."), "stdin", str("Optional stdin."), "timeoutSec", integer("Timeout seconds."), "dryRun", bool("Dry-run command construction.")), List.of()), false));
        }
        return out;
    }

    private Object callTool(Map<String, Object> params, AuthContext auth) {
        String name = String.valueOf(params.getOrDefault("name", ""));
        Map<String, Object> args = mapAt(params, "arguments");
        List<String> requiredScopes = scopes.requiredForMcpTool(name);
        if (!authService.hasRequiredScopes(auth, requiredScopes)) {
            audit.toolRejected(name, auth, requiredScopes, scopes.riskTier(name));
            throw new McpException(-32001, "insufficient_scope: required " + String.join(" ", requiredScopes));
        }
        audit.toolCall(name, auth, scopes.riskTier(name));
        Object payload = switch (name) {
            case "workspace.list" -> workspaceService.list(strAt(args, "path", "."), intAt(args, "limit", 100));
            case "workspace.tree" -> workspaceService.tree(strAt(args, "path", "."), intAt(args, "depth", 3), intAt(args, "limit", 500));
            case "workspace.read" -> workspaceService.read(strAt(args, "path", ""), intAt(args, "offset", 0), intAt(args, "maxBytes", 65536));
            case "workspace.search" -> workspaceService.search(strAt(args, "query", strAt(args, "q", "")), strAt(args, "path", "."), intAt(args, "limit", 100), boolAt(args, "regex", false), boolAt(args, "caseSensitive", false));
            case "workspace.write" -> workspaceService.write(new WorkspaceWriteRequest(strAt(args, "path", ""), strAt(args, "content", ""), boolAt(args, "createParents", true), boolAt(args, "dryRun", false), strAt(args, "expectedSha256", "")));
            case "workspace.mkdir" -> workspaceService.mkdir(strAt(args, "path", ""), boolAt(args, "dryRun", false));
            case "workspace.delete" -> workspaceService.delete(new WorkspaceDeleteRequest(strAt(args, "path", ""), boolAt(args, "recursive", false), boolAt(args, "dryRun", true)));
            case "toolbelt.search" -> toolbeltService.search(strAt(args, "query", strAt(args, "q", "")), intAt(args, "limit", 50), "", "", null, "");
            case "toolbelt.inventory" -> toolbeltService.inventory();
            case "basicKnowledge.remember" -> basicKnowledgeStore.remember(strAt(args, "key", ""), strAt(args, "value", ""), listAt(args, "tags"), strAt(args, "source", "agent"));
            case "basicKnowledge.search" -> basicKnowledgeStore.search(strAt(args, "query", strAt(args, "q", "")));
            case "basicKnowledge.list" -> basicKnowledgeStore.list();
            case "basicKnowledge.dump" -> basicKnowledgeStore.dump();
            case "toolbelt.run" -> toolbeltService.run(toolRunRequest(strAt(args, "toolId", ""), args));
            case "command.execute" -> commandRegistry.executeRaw(strAt(args, "line", ""));
            case "fn.list" -> fnList();
            case "fn.trigger" -> triggerFn(args);
            case "desktop.screenshot.send" -> desktopScreenshot(args, "direct");
            case "desktop.form.relay.current" -> desktopFormRelay.currentRequest();
            case "desktop.form.relay.status" -> desktopFormRelay.status(strAt(args, "relayId", ""));
            case "desktop.form.relay.submit" -> desktopFormRelay.submit(args);
            default -> dynamicTool(name, args);
        };
        return toolResult(payload);
    }


    private Map<String, Object> fnList() {
        ArrayList<Map<String, Object>> buttons = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String code = String.format("FN-%02d", i);
            buttons.add(orderedMap(
                    "code", code,
                    "index", i,
                    "enabled", i == 12,
                    "title", i == 12 ? "Send Desktop Screenshot" : "Unassigned",
                    "route", i == 12 ? "desktop.screenshot.send" : "",
                    "destination", i == 12 ? "active-chat" : "",
                    "configPath", System.getProperty("suite.config.dir", "config") + "/suite-fn.yml"
            ));
        }
        return orderedMap(
                "schema", "spring-suite.fn_registry.v1",
                "count", buttons.size(),
                "buttons", buttons
        );
    }

    private Object triggerFn(Map<String, Object> args) {
        String code = normalizeFnButton(strAt(args, "fn", strAt(args, "button", "")));
        if (code.isBlank()) {
            throw new McpException(-32602, "missing or invalid FN button");
        }
        if (code.equals("FN-12")) {
            return desktopScreenshot(args, code);
        }
        return orderedMap(
                "schema", "spring-suite.fn_trigger.v1",
                "ok", false,
                "fn", code,
                "message", code + " is reserved but not assigned yet"
        );
    }

    private String normalizeFnButton(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.isBlank()) {
            return "";
        }
        value = value.replace("_", "-").replace(" ", "");
        if (value.matches("\\d{1,2}")) {
            value = "FN-" + String.format("%02d", Integer.parseInt(value));
        } else if (value.matches("F\\d{1,2}")) {
            value = "FN-" + String.format("%02d", Integer.parseInt(value.substring(1)));
        } else if (value.matches("FN\\d{1,2}")) {
            value = "FN-" + String.format("%02d", Integer.parseInt(value.substring(2)));
        }
        if (!value.matches("FN-\\d{2}")) {
            return "";
        }
        int index = Integer.parseInt(value.substring(3));
        return index >= 1 && index <= 12 ? value : "";
    }


    private Map<String, Object> desktopScreenshot(Map<String, Object> args, String source) {
        String target = strAt(args, "target", "virtual");
        int maxWidth = intAt(args, "maxWidth", intAt(args, "max_width", 0));
        ToolRunResult run = toolbeltService.run(new ToolRunRequest(
                "suite-desktop-capture",
                List.of("screenshot", "--target", target, "--max-width", String.valueOf(maxWidth), "--json", "--base64=true"),
                "",
                "",
                intAt(args, "timeoutSec", 0),
                intAt(args, "maxStdoutBytes", 0),
                intAt(args, "maxStderrBytes", 0),
                false
        ));
        if (!run.ok()) {
            throw new McpException(-32000, "desktop image helper failed: " + run.message() + " " + run.stderr());
        }
        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(run.stdout().trim(), MAP_TYPE);
        } catch (Exception ex) {
            throw new McpException(-32000, "desktop image helper returned invalid JSON: " + ex.getMessage());
        }
        String mimeType = strAt(parsed, "mimeType", "image/png");
        String data = strAt(parsed, "base64", "");
        if (data.isBlank()) {
            throw new McpException(-32000, "desktop image helper did not include image data");
        }
        Map<String, Object> structured = orderedMap(
                "schema", "spring-suite.desktop_screenshot_handoff.v1",
                "ok", true,
                "source", source,
                "tool", "desktop.screenshot.send",
                "mimeType", mimeType,
                "width", parsed.getOrDefault("width", 0),
                "height", parsed.getOrDefault("height", 0),
                "originalWidth", parsed.getOrDefault("originalWidth", parsed.getOrDefault("width", 0)),
                "originalHeight", parsed.getOrDefault("originalHeight", parsed.getOrDefault("height", 0)),
                "scaled", parsed.getOrDefault("scaled", false),
                "sha256", parsed.getOrDefault("sha256", ""),
                "pngBytes", parsed.getOrDefault("pngBytes", 0),
                "capturedAt", parsed.getOrDefault("capturedAt", Instant.now().toString()),
                "target", parsed.getOrDefault("target", target)
        );
        return orderedMap(
                "_mcpContent", List.of(
                        orderedMap("type", "text", "text", "SpringSuite " + source + " returned " + mimeType + "."),
                        orderedMap("type", "image", "mimeType", mimeType, "data", data)
                ),
                "structuredContent", structured
        );
    }

    private Object dynamicTool(String name, Map<String, Object> args) {
        for (ToolIndexEntry entry : toolbeltService.index()) {
            if (entry.publicName().equals(name)) {
                return toolbeltService.run(toolRunRequest(entry.id(), args));
            }
        }
        throw new McpException(-32602, "unknown tool: " + name);
    }

    private List<String> listAt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(item -> item == null ? "" : String.valueOf(item)).filter(item -> !item.isBlank()).toList();
    }

    private ToolRunRequest toolRunRequest(String toolId, Map<String, Object> args) {
        return new ToolRunRequest(
                toolId,
                stringList(args.get("args")),
                strAt(args, "cwd", ""),
                strAt(args, "stdin", ""),
                intAt(args, "timeoutSec", intAt(args, "timeout_sec", 0)),
                intAt(args, "maxStdoutBytes", 0),
                intAt(args, "maxStderrBytes", 0),
                boolAt(args, "dryRun", false)
        );
    }

    private Map<String, Object> toolResult(Object payload) {
        if (payload instanceof Map<?, ?> map && map.containsKey("_mcpContent")) {
            Object structured = map.containsKey("structuredContent") ? map.get("structuredContent") : Map.of();
            return orderedMap(
                    "content", map.get("_mcpContent"),
                    "structuredContent", structuredContentObject(structured),
                    "isError", false
            );
        }
        return orderedMap(
                "content", List.of(Map.of("type", "text", "text", String.valueOf(payload))),
                "structuredContent", structuredContentObject(payload),
                "isError", false
        );
    }

    private Map<String, Object> structuredContentObject(Object payload) {
        if (payload == null) {
            return orderedMap("value", null);
        }
        if (payload instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        if (payload instanceof List<?> list) {
            return orderedMap(
                    "items", list,
                    "count", list.size()
            );
        }
        if (payload instanceof String || payload instanceof Number || payload instanceof Boolean || payload instanceof Character) {
            return orderedMap("value", payload);
        }
        try {
            return objectMapper.convertValue(payload, MAP_TYPE);
        } catch (IllegalArgumentException ex) {
            return orderedMap("value", payload);
        }
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema, boolean readOnly) {
        return orderedMap(
                "name", name,
                "title", name,
                "description", description,
                "inputSchema", inputSchema,
                "outputSchema", outputSchema(name),
                "annotations", orderedMap("readOnlyHint", readOnly, "destructiveHint", name.equals("workspace.delete"), "idempotentHint", readOnly),
                "_meta", orderedMap("northstar/scopes", scopes.requiredForMcpTool(name), "northstar/riskTier", scopes.riskTier(name))
        );
    }

    private Map<String, Object> outputSchema(String name) {
        return switch (name) {
            case "workspace.list", "workspace.tree" -> workspaceListingOutputSchema();
            case "workspace.read" -> schema(orderedMap(
                    "path", str("Resolved workspace path."),
                    "sizeBytes", integer("Total file size in bytes."),
                    "offset", integer("Read offset in bytes."),
                    "bytesRead", integer("Number of bytes returned."),
                    "truncated", bool("True when the returned content was truncated by maxBytes."),
                    "sha256", str("SHA-256 hash of the full file."),
                    "content", str("UTF-8 file content chunk.")
            ), List.of("path", "sizeBytes", "offset", "bytesRead", "truncated", "sha256", "content"));
            case "workspace.search" -> schema(orderedMap(
                    "query", str("Search query."),
                    "path", str("Resolved workspace search root."),
                    "regex", bool("True when regex mode was used."),
                    "caseSensitive", bool("True when case-sensitive matching was used."),
                    "truncated", bool("True when result count was limited."),
                    "count", integer("Number of returned matches."),
                    "matches", arrayOf("Matching text lines.", workspaceSearchMatchOutputSchema())
            ), List.of("query", "path", "regex", "caseSensitive", "truncated", "count", "matches"));
            case "workspace.write" -> schema(orderedMap(
                    "ok", bool("True when the write was accepted."),
                    "path", str("Resolved workspace path."),
                    "created", bool("True when a new file was created."),
                    "dryRun", bool("True when no file was changed."),
                    "backupPath", str("Backup path created before replacing an existing file, or empty string."),
                    "bytesWritten", integer("Number of bytes written."),
                    "sha256", str("SHA-256 hash after the write."),
                    "message", str("Operation status message.")
            ), List.of("ok", "path", "created", "dryRun", "backupPath", "bytesWritten", "sha256", "message"));
            case "workspace.mkdir", "workspace.delete" -> schema(orderedMap(
                    "ok", bool("True when the mutation was accepted."),
                    "path", str("Resolved workspace path."),
                    "dryRun", bool("True when no filesystem mutation was performed."),
                    "message", str("Operation status message.")
            ), List.of("ok", "path", "dryRun", "message"));
            case "toolbelt.search" -> listResultOutputSchema("Discovered tool descriptors matching the query.", looseObjectSchema("Tool descriptor."));
            case "toolbelt.inventory" -> looseObjectSchema("Toolbelt inventory and diagnostics.");
            case "toolbelt.run" -> toolRunResultOutputSchema();
            case "basicKnowledge.remember" -> basicKnowledgeItemOutputSchema();
            case "basicKnowledge.search", "basicKnowledge.list" -> listResultOutputSchema("BasicKnowledge facts.", basicKnowledgeItemOutputSchema());
            case "basicKnowledge.dump" -> schema(orderedMap(
                    "schema", str("BasicKnowledge storage schema identifier."),
                    "path", str("Storage file path."),
                    "count", integer("Number of stored facts."),
                    "items", arrayOf("Stored facts.", basicKnowledgeItemOutputSchema())
            ), List.of("schema", "path", "count", "items"));
            case "command.execute" -> looseObjectSchema("SpringSuite command execution result.");
            case "fn.list" -> schema(orderedMap(
                    "schema", str("FN registry schema identifier."),
                    "count", integer("Number of FN buttons."),
                    "buttons", arrayOf("FN button descriptors.", looseObjectSchema("FN button descriptor."))
            ), List.of("schema", "count", "buttons"));
            case "desktop.screenshot.send" -> desktopScreenshotOutputSchema();
            case "desktop.form.relay.current", "desktop.form.relay.status", "desktop.form.relay.submit"
                    -> flexibleStructuredOutputSchema("ChatGPT form relay state or submission result.");
            case "fn.trigger" -> flexibleStructuredOutputSchema("FN trigger result or desktop screenshot handoff.");
            default -> name.startsWith("tool_") ? toolRunResultOutputSchema() : flexibleStructuredOutputSchema("Structured result returned by " + name + ".");
        };
    }

    private Map<String, Object> workspaceListingOutputSchema() {
        return schema(orderedMap(
                "path", str("Resolved workspace path."),
                "truncated", bool("True when the entry list was limited."),
                "count", integer("Number of returned entries."),
                "entries", arrayOf("Workspace entries.", workspaceEntryOutputSchema())
        ), List.of("path", "truncated", "count", "entries"));
    }

    private Map<String, Object> workspaceEntryOutputSchema() {
        return schema(orderedMap(
                "path", str("Workspace-relative path."),
                "name", str("Entry name."),
                "directory", bool("True when the entry is a directory."),
                "regularFile", bool("True when the entry is a regular file."),
                "sizeBytes", integer("Entry size in bytes."),
                "modifiedAt", str("Last modification timestamp.")
        ), List.of("path", "name", "directory", "regularFile", "sizeBytes", "modifiedAt"));
    }

    private Map<String, Object> workspaceSearchMatchOutputSchema() {
        return schema(orderedMap(
                "path", str("Matched file path."),
                "lineNumber", integer("One-based matched line number."),
                "line", str("Matched line content.")
        ), List.of("path", "lineNumber", "line"));
    }

    private Map<String, Object> basicKnowledgeItemOutputSchema() {
        return schema(orderedMap(
                "id", str("Stable item id."),
                "key", str("Stable item key."),
                "value", str("Stored fact value."),
                "tags", array("Tags."),
                "source", str("Source label."),
                "createdAt", str("Creation timestamp."),
                "updatedAt", str("Last update timestamp.")
        ), List.of("id", "key", "value", "tags", "source", "createdAt", "updatedAt"));
    }

    private Map<String, Object> toolRunResultOutputSchema() {
        return schema(orderedMap(
                "ok", bool("True when the tool process completed successfully."),
                "toolId", str("Resolved tool id."),
                "commandPreview", array("Command line preview."),
                "cwd", str("Working directory used by the process."),
                "exitCode", nullable(integer("Process exit code, or null if the process did not start.")),
                "durationMs", integer("Execution duration in milliseconds."),
                "stdout", str("Captured standard output."),
                "stderr", str("Captured standard error."),
                "message", str("Execution status message."),
                "dryRun", bool("True when command construction was validated without execution."),
                "timestamp", str("Completion timestamp.")
        ), List.of("ok", "toolId", "commandPreview", "cwd", "exitCode", "durationMs", "stdout", "stderr", "message", "dryRun", "timestamp"));
    }

    private Map<String, Object> desktopScreenshotOutputSchema() {
        return schema(orderedMap(
                "schema", str("Desktop screenshot handoff schema identifier."),
                "ok", bool("True when the screenshot was captured."),
                "source", str("Invocation source."),
                "tool", str("MCP tool name."),
                "mimeType", str("Image MIME type."),
                "width", integer("Returned image width."),
                "height", integer("Returned image height."),
                "originalWidth", integer("Original capture width."),
                "originalHeight", integer("Original capture height."),
                "scaled", bool("True when the capture was scaled."),
                "sha256", str("Image SHA-256 hash."),
                "pngBytes", integer("PNG byte size."),
                "capturedAt", str("Capture timestamp."),
                "target", str("Capture target.")
        ), List.of("schema", "ok", "source", "tool", "mimeType", "width", "height", "originalWidth", "originalHeight", "scaled", "sha256", "pngBytes", "capturedAt", "target"));
    }

    private Map<String, Object> flexibleStructuredOutputSchema(String description) {
        return orderedMap(
                "description", description,
                "anyOf", List.of(
                        looseObjectSchema("Object structured content."),
                        arrayOf("Array structured content.", orderedMap()),
                        Map.of("type", "string"),
                        Map.of("type", "number"),
                        Map.of("type", "integer"),
                        Map.of("type", "boolean"),
                        Map.of("type", "null")
                )
        );
    }

    private Map<String, Object> listResultOutputSchema(String description, Map<String, Object> itemSchema) {
        return schema(orderedMap(
                "items", arrayOf(description, itemSchema),
                "count", integer("Number of returned items.")
        ), List.of("items", "count"));
    }

    private Map<String, Object> looseObjectSchema(String description) {
        return orderedMap("type", "object", "description", description, "additionalProperties", true);
    }

    private Map<String, Object> arrayOf(String description, Map<String, Object> itemSchema) {
        return orderedMap("type", "array", "description", description, "items", itemSchema);
    }

    private Map<String, Object> nullable(Map<String, Object> schema) {
        return orderedMap("anyOf", List.of(schema, Map.of("type", "null")));
    }

    private Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return orderedMap("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    private Map<String, Object> str(String description) {
        return orderedMap("type", "string", "description", description);
    }

    private Map<String, Object> integer(String description) {
        return orderedMap("type", "integer", "description", description);
    }

    private Map<String, Object> bool(String description) {
        return orderedMap("type", "boolean", "description", description);
    }

    private Map<String, Object> array(String description) {
        return orderedMap("type", "array", "items", Map.of("type", "string"), "description", description);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> params(Map<String, Object> request) {
        Object params = request.get("params");
        return params instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapAt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private String strAt(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private int intAt(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean boolAt(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean bool ? bool : value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private List<String> stringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private LinkedHashMap<String, Object> orderedMap(Object... values) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private static class McpException extends RuntimeException {
        private final int code;

        McpException(int code, String message) {
            super(message);
            this.code = code;
        }

        int code() {
            return code;
        }
    }
}
