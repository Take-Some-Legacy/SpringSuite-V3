package com.takesome.springsuite.fnmodule;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class FnCommand implements SuiteCommand {
    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "fn",
                List.of("fkey", "function", "function-key"),
                "operator",
                "Inspect and trigger the 12 SpringSuite FN operator buttons.",
                "FN module exposes twelve explicit operator actions. FN-12 is configured to route to desktop.screenshot.send for active-chat handoff.",
                "fn <list|show|trigger> [FN-01..FN-12]",
                CommandRiskLevel.PROCESS_CONTROL
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String action = invocation.arg(0).isBlank() ? "list" : invocation.arg(0).trim().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "list", "ls", "status" -> list();
            case "show", "info" -> show(invocation.arg(1));
            case "trigger", "press", "run" -> trigger(invocation.arg(1));
            default -> show(action);
        };
    }

    private CommandExecutionResult list() {
        FnConfig config = FnConfigLoader.load();
        LinkedHashMap<String, Object> data = base(config);
        data.put("buttons", config.buttons().stream().map(FnBinding::toMap).toList());
        data.put("configPath", FnConfigLoader.configPath().toString());
        return CommandExecutionResult.ok("FN buttons: " + config.buttons().size(), data);
    }

    private CommandExecutionResult show(String rawCode) {
        FnConfig config = FnConfigLoader.load();
        String code = normalize(rawCode);
        if (code.isBlank()) {
            return CommandExecutionResult.failed("fn_missing_code", "usage: fn show FN-12");
        }
        Optional<FnBinding> binding = config.buttons().stream()
                .filter(item -> item.code().equalsIgnoreCase(code))
                .findFirst();
        if (binding.isEmpty()) {
            return CommandExecutionResult.failed("fn_not_found", "FN button not found: " + code);
        }
        LinkedHashMap<String, Object> data = base(config);
        data.put("button", binding.get().toMap());
        return CommandExecutionResult.ok(code, data);
    }

    private CommandExecutionResult trigger(String rawCode) {
        FnConfig config = FnConfigLoader.load();
        if (!config.enabled()) {
            return CommandExecutionResult.failed("fn_disabled", "suite.fn.enabled=false");
        }
        String code = normalize(rawCode);
        if (code.isBlank()) {
            return CommandExecutionResult.failed("fn_missing_code", "usage: fn trigger FN-12");
        }
        Optional<FnBinding> binding = config.buttons().stream()
                .filter(item -> item.code().equalsIgnoreCase(code))
                .findFirst();
        if (binding.isEmpty()) {
            return CommandExecutionResult.failed("fn_not_found", "FN button not found: " + code);
        }
        FnBinding item = binding.get();
        if (!item.enabled()) {
            return CommandExecutionResult.failed("fn_unassigned", code + " is reserved but not assigned");
        }
        if (item.route().isBlank()) {
            return CommandExecutionResult.failed("fn_missing_route", code + " has no route");
        }
        LinkedHashMap<String, Object> data = base(config);
        data.put("button", item.toMap());
        data.put("dispatch", Map.of(
                "type", "mcp-tool-route",
                "tool", item.route(),
                "destination", item.destination(),
                "args", item.args(),
                "explicitOperatorAction", true
        ));
        return CommandExecutionResult.ok(code + " routes to " + item.route(), data);
    }

    private LinkedHashMap<String, Object> base(FnConfig config) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("schema", "spring-suite.fn-module.v1");
        data.put("enabled", config.enabled());
        data.put("buttonCount", config.buttonCount());
        data.put("namespace", config.namespace());
        data.put("dispatchMode", config.dispatchMode());
        data.put("defaultDestination", config.defaultDestination());
        return data;
    }

    private String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.isBlank()) {
            return "";
        }
        value = value.replace("_", "-").replace(" ", "");
        if (value.matches("\\d{1,2}")) {
            return String.format("FN-%02d", Integer.parseInt(value));
        }
        if (value.matches("F\\d{1,2}")) {
            return String.format("FN-%02d", Integer.parseInt(value.substring(1)));
        }
        if (value.matches("FN\\d{1,2}")) {
            return String.format("FN-%02d", Integer.parseInt(value.substring(2)));
        }
        return value;
    }
}
