package com.takesome.springsuite.ai;

import com.takesome.springsuite.core.ai.AiChatRequest;
import com.takesome.springsuite.core.ai.AiGenerationOptions;
import com.takesome.springsuite.core.ai.AiMessage;
import com.takesome.springsuite.core.ai.AiRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AiHttpRequestMapper {
    private AiHttpRequestMapper() {
    }

    @SuppressWarnings("unchecked")
    public static AiChatRequest fromMap(Map<String, Object> body) {
        Map<String, Object> safe = body == null ? Map.of() : body;
        String providerId = string(safe.get("providerId"));
        String model = string(safe.get("model"));
        ArrayList<AiMessage> messages = new ArrayList<>();
        Object rawMessages = safe.get("messages");
        if (rawMessages instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    messages.add(new AiMessage(
                            AiRole.from(string(map.get("role"))),
                            string(map.get("content")),
                            string(map.get("name")),
                            string(map.get("toolCallId")),
                            Map.of()
                    ));
                }
            }
        }
        String input = string(safe.get("input"));
        if (messages.isEmpty() && !input.isBlank()) {
            messages.add(AiMessage.user(input));
        }
        AiGenerationOptions options = options(safe.get("options"));
        return new AiChatRequest(providerId, model, messages, options, List.of(), Map.of());
    }

    @SuppressWarnings("unchecked")
    private static AiGenerationOptions options(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return AiGenerationOptions.defaults();
        }
        return new AiGenerationOptions(
                integer(map.get("maxTokens")),
                decimal(map.get("temperature")),
                decimal(map.get("topP")),
                bool(map.get("stream")),
                string(map.get("reasoningEffort")),
                bool(map.get("thinking")),
                bool(map.get("store")),
                map.get("vendorOptions") instanceof Map<?, ?> vendor ? (Map<String, Object>) vendor : Map.of()
        );
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String text = string(value);
            return text.isBlank() ? null : Integer.parseInt(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Double decimal(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            String text = string(value);
            return text.isBlank() ? null : Double.parseDouble(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = string(value);
        return text.isBlank() ? null : Boolean.parseBoolean(text);
    }
}
