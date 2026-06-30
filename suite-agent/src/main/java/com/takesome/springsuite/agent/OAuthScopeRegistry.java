package com.takesome.springsuite.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OAuthScopeRegistry {
    public static final String READ = "northstar.read";
    public static final String WRITE = "northstar.write";
    public static final String EXEC = "northstar.exec";
    public static final String ADMIN = "northstar.admin";

    public List<String> normalizeRequested(String rawScope, List<String> supported, List<String> fallback) {
        Set<String> supportedSet = supported == null || supported.isEmpty()
                ? Set.of(READ, WRITE, EXEC, ADMIN)
                : new LinkedHashSet<>(supported);
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        if (rawScope != null) {
            for (String part : rawScope.split("\\s+")) {
                if (supportedSet.contains(part)) {
                    scopes.add(part);
                }
            }
        }
        if (scopes.isEmpty()) {
            for (String scope : fallback == null || fallback.isEmpty() ? List.of(READ, WRITE) : fallback) {
                if (supportedSet.contains(scope)) {
                    scopes.add(scope);
                }
            }
        }
        return new ArrayList<>(scopes);
    }

    public String join(List<String> scopes) {
        return String.join(" ", scopes == null ? List.of() : scopes);
    }

    public List<String> requiredForMcpTool(String name) {
        if (name == null) {
            return List.of(READ);
        }
        String tool = name.trim();
        if (tool.equals("workspace.write") || tool.equals("workspace.mkdir")) {
            return List.of(READ, WRITE);
        }
        if (tool.equals("workspace.delete")) {
            return List.of(READ, WRITE, ADMIN);
        }
        if (tool.equals("command.execute") || tool.equals("toolbelt.run") || tool.startsWith("tool_")) {
            return List.of(READ, WRITE, EXEC);
        }
        return List.of(READ);
    }

    public String riskTier(String name) {
        if (name == null) {
            return "read_only";
        }
        String tool = name.trim();
        if (tool.equals("workspace.delete")) {
            return "dangerous";
        }
        if (tool.equals("workspace.write") || tool.equals("workspace.mkdir")) {
            return "write";
        }
        if (tool.equals("command.execute") || tool.equals("toolbelt.run") || tool.startsWith("tool_")) {
            return "exec";
        }
        return "read_only";
    }
}
