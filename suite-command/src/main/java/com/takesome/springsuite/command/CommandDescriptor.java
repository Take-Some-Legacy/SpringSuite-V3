package com.takesome.springsuite.command;

import java.util.List;

public record CommandDescriptor(
        String name,
        List<String> aliases,
        String category,
        String summary,
        String description,
        String usage,
        CommandRiskLevel riskLevel
) {
    public CommandDescriptor {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        category = category == null || category.isBlank() ? "general" : category.trim();
        summary = summary == null ? "" : summary.trim();
        description = description == null ? "" : description.trim();
        usage = usage == null ? name : usage.trim();
        riskLevel = riskLevel == null ? CommandRiskLevel.READ_ONLY : riskLevel;
    }
}
