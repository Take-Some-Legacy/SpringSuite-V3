package com.takesome.springsuite.agent;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandRegistry;
import com.takesome.springsuite.agent.audit.AgentAuditService;
import com.takesome.springsuite.toolbelt.ToolDescriptor;
import com.takesome.springsuite.toolbelt.ToolIndexEntry;
import com.takesome.springsuite.toolbelt.ToolRunRequest;
import com.takesome.springsuite.toolbelt.ToolbeltService;
import com.takesome.springsuite.workspace.WorkspaceDeleteRequest;
import com.takesome.springsuite.workspace.WorkspaceService;
import com.takesome.springsuite.workspace.WorkspaceWriteRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class McpService {
    private final SuiteMcpProperties properties;
    private final SuiteAuthService authService;
    private final OAuthScopeRegistry scopes;
    private final WorkspaceService workspaceService;
    private final ToolbeltService toolbeltService;
    private final CommandRegistry commandRegistry;
    private final BasicKnowledgeStore basicKnowledgeStore;
    private final AgentAuditService audit;

    public McpService(
            SuiteMcpProperties properties,
            SuiteAuthService authService,
            OAuthScopeRegistry scopes,
            WorkspaceService workspaceService,
            ToolbeltService toolbeltService,
            CommandRegistry commandRegistry,
            BasicKnowledgeStore basicKnowledgeStore,
            AgentAuditService audit
    ) {
        this.properties = properties;
        this.authService = authService;
        this.scopes = scopes;
        this.workspaceService = workspaceService;
        this.toolbeltService = toolbeltService;
        this.commandRegistry = commandRegistry;
        this.basicKnowledgeStore = basicKnowledgeStore;
        this.audit = audit;
    }

    public Map<String, Object> discovery(String baseUrl) {
        return orderedMap(
                "ok", true,
                "name", properties.getServerName(),
                "version", "0.1.10",
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
                "version", "0.1.10",
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
            default -> dynamicTool(name, args);
        };
        return toolResult(payload);
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
                intAt(args, "maxStdoutBytes", 12000),
                intAt(args, "maxStderrBytes", 8000),
                boolAt(args, "dryRun", false)
        );
    }

    private Map<String, Object> toolResult(Object payload) {
        return orderedMap(
                "content", List.of(Map.of("type", "text", "text", String.valueOf(payload))),
                "structuredContent", payload,
                "isError", false
        );
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema, boolean readOnly) {
        return orderedMap(
                "name", name,
                "title", name,
                "description", description,
                "inputSchema", inputSchema,
                "annotations", orderedMap("readOnlyHint", readOnly, "destructiveHint", name.equals("workspace.delete"), "idempotentHint", readOnly),
                "_meta", orderedMap("northstar/scopes", scopes.requiredForMcpTool(name), "northstar/riskTier", scopes.riskTier(name))
        );
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
