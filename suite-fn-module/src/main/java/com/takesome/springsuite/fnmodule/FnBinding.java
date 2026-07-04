package com.takesome.springsuite.fnmodule;

import java.util.LinkedHashMap;
import java.util.Map;

public record FnBinding(
        String code,
        int index,
        boolean enabled,
        String title,
        String route,
        String riskTier,
        String destination,
        Map<String, String> args
) {
    public FnBinding {
        code = code == null || code.isBlank() ? "FN-00" : code.trim().toUpperCase();
        title = title == null ? "" : title.trim();
        route = route == null ? "" : route.trim();
        riskTier = riskTier == null || riskTier.isBlank() ? "none" : riskTier.trim();
        destination = destination == null || destination.isBlank() ? "active-chat" : destination.trim();
        args = args == null ? Map.of() : Map.copyOf(args);
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("index", index);
        out.put("enabled", enabled);
        out.put("title", title);
        out.put("route", route);
        out.put("riskTier", riskTier);
        out.put("destination", destination);
        out.put("args", args);
        return out;
    }
}
