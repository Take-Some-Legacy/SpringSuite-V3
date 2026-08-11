package com.takesome.springsuite.desktop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FormRelayCommand implements SuiteCommand {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ChatGptFormRelayService relayService;
    private final ObjectMapper objectMapper;

    public FormRelayCommand(ChatGptFormRelayService relayService, ObjectMapper objectMapper) {
        this.relayService = relayService;
        this.objectMapper = objectMapper;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "form-relay",
                List.of("relay", "chatgpt-relay"),
                "desktop",
                "Exchange active-form drafts with ChatGPT through NorthStar MCP.",
                "Returns a privacy-filtered active form and accepts a non-executing operator draft.",
                "form-relay current|status [relayId]|submit <relayId> <base64url-json>",
                CommandRiskLevel.LOCAL_MUTATION
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String action = invocation.arg(0).isBlank() ? "status" : invocation.arg(0).toLowerCase();
        return switch (action) {
            case "current" -> CommandExecutionResult.ok("active form relay", relayService.currentRequest());
            case "status" -> CommandExecutionResult.ok("form relay status", relayService.status(invocation.arg(1)));
            case "submit" -> submit(invocation);
            default -> CommandExecutionResult.failed("unknown_subcommand", "Usage: " + descriptor().usage());
        };
    }

    private CommandExecutionResult submit(CommandInvocation invocation) {
        String relayId = invocation.arg(1);
        String encoded = invocation.arg(2);
        if (relayId.isBlank() || encoded.isBlank()) {
            return CommandExecutionResult.failed("relay_submit_arguments_missing", "Usage: " + descriptor().usage());
        }
        try {
            byte[] jsonBytes = Base64.getUrlDecoder().decode(pad(encoded));
            Map<String, Object> parsed = objectMapper.readValue(new String(jsonBytes, StandardCharsets.UTF_8), MAP_TYPE);
            LinkedHashMap<String, Object> request = new LinkedHashMap<>(parsed);
            request.put("relayId", relayId);
            Map<String, Object> result = relayService.submit(request);
            boolean ok = Boolean.TRUE.equals(result.get("ok"));
            if (!ok) {
                return CommandExecutionResult.failed(
                        String.valueOf(result.getOrDefault("code", "relay_submit_failed")),
                        String.valueOf(result.getOrDefault("message", "Relay draft was rejected."))
                );
            }
            return CommandExecutionResult.ok("ChatGPT relay draft accepted", result);
        } catch (Exception ex) {
            return CommandExecutionResult.failed(
                    "relay_payload_invalid",
                    "Expected base64url-encoded JSON payload: " + safeMessage(ex)
            );
        }
    }

    private String pad(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "=".repeat(4 - remainder);
    }

    private String safeMessage(Throwable ex) {
        String message = ex == null ? "" : ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
