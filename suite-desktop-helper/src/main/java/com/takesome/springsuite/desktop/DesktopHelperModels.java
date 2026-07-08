package com.takesome.springsuite.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DesktopHelperModels {
    private DesktopHelperModels() {
    }

    public record DesktopHelperStatus(
            boolean enabled,
            String mode,
            boolean aiEnrichmentEnabled,
            boolean captureToolAvailable,
            String captureToolId,
            List<String> endpoints,
            List<String> enabledSurfaces,
            Map<String, Object> policy
    ) {
        public DesktopHelperStatus {
            mode = text(mode);
            captureToolId = text(captureToolId);
            endpoints = list(endpoints);
            enabledSurfaces = list(enabledSurfaces);
            policy = safeMap(policy);
        }
    }

    public record DesktopCapabilitySchema(
            String module,
            boolean enabled,
            String mode,
            List<DesktopIntegrationSurface> surfaces,
            List<DesktopSafetyRule> safetyRules,
            List<DesktopWorkflow> workflows,
            List<DesktopActionContract> actionContracts,
            Map<String, Object> sidecarContract
    ) {
        public DesktopCapabilitySchema {
            module = textOr(module, "suite-desktop-helper");
            mode = textOr(mode, "assistive");
            surfaces = list(surfaces);
            safetyRules = list(safetyRules);
            workflows = list(workflows);
            actionContracts = list(actionContracts);
            sidecarContract = safeMap(sidecarContract);
        }
    }

    public record DesktopIntegrationSurface(
            String id,
            String title,
            String access,
            boolean enabled,
            String adapter,
            List<String> capabilities,
            String safety
    ) {
        public DesktopIntegrationSurface {
            id = text(id);
            title = text(title);
            access = textOr(access, "read");
            adapter = text(adapter);
            capabilities = list(capabilities);
            safety = text(safety);
        }
    }

    public record DesktopSafetyRule(
            String id,
            String title,
            String description,
            String enforcement
    ) {
        public DesktopSafetyRule {
            id = text(id);
            title = text(title);
            description = text(description);
            enforcement = text(enforcement);
        }
    }

    public record DesktopWorkflow(
            String id,
            String title,
            String description,
            List<String> endpoints,
            List<String> requiredApprovals
    ) {
        public DesktopWorkflow {
            id = text(id);
            title = text(title);
            description = text(description);
            endpoints = list(endpoints);
            requiredApprovals = list(requiredApprovals);
        }
    }

    public record DesktopActionContract(
            String id,
            String title,
            String direction,
            String approval,
            List<String> inputs,
            List<String> outputs
    ) {
        public DesktopActionContract {
            id = text(id);
            title = text(title);
            direction = textOr(direction, "read");
            approval = textOr(approval, "none");
            inputs = list(inputs);
            outputs = list(outputs);
        }
    }

    public record DesktopFocusContext(
            String platform,
            String activeApplication,
            String activeWindowTitle,
            String url,
            String focusedElementRole,
            String focusedElementName,
            String selectedText,
            String clipboardPreview,
            String screenText,
            DesktopFormContext form,
            Map<String, Object> metadata
    ) {
        public DesktopFocusContext {
            platform = text(platform);
            activeApplication = text(activeApplication);
            activeWindowTitle = text(activeWindowTitle);
            url = text(url);
            focusedElementRole = text(focusedElementRole);
            focusedElementName = text(focusedElementName);
            selectedText = text(selectedText);
            clipboardPreview = text(clipboardPreview);
            screenText = text(screenText);
            form = form == null ? DesktopFormContext.empty() : form;
            metadata = safeMap(metadata);
        }

        public static DesktopFocusContext empty() {
            return new DesktopFocusContext("", "", "", "", "", "", "", "", "", DesktopFormContext.empty(), Map.of());
        }
    }

    public record DesktopFormContext(
            String id,
            String name,
            String action,
            String method,
            List<DesktopFormField> fields,
            Map<String, Object> metadata
    ) {
        public DesktopFormContext {
            id = text(id);
            name = text(name);
            action = text(action);
            method = text(method);
            fields = list(fields);
            metadata = safeMap(metadata);
        }

        public static DesktopFormContext empty() {
            return new DesktopFormContext("", "", "", "", List.of(), Map.of());
        }
    }

    public record DesktopFormField(
            String id,
            String label,
            String name,
            String type,
            String value,
            String placeholder,
            boolean required,
            boolean focused,
            boolean sensitive,
            List<String> options,
            Map<String, Object> metadata
    ) {
        public DesktopFormField {
            id = text(id);
            label = text(label);
            name = text(name);
            type = textOr(type, "text");
            value = text(value);
            placeholder = text(placeholder);
            options = list(options);
            metadata = safeMap(metadata);
        }

        public String displayName() {
            if (!label.isBlank()) {
                return label;
            }
            if (!name.isBlank()) {
                return name;
            }
            return id;
        }
    }

    public record DesktopHintRequest(
            DesktopFocusContext context,
            String userGoal,
            String locale,
            Map<String, Object> preferences
    ) {
        public DesktopHintRequest {
            context = context == null ? DesktopFocusContext.empty() : context;
            userGoal = text(userGoal);
            locale = textOr(locale, "en-US");
            preferences = safeMap(preferences);
        }
    }

    public record DesktopHintResponse(
            boolean ok,
            String summary,
            List<DesktopHint> hints,
            String aiSuggestion,
            Map<String, Object> metadata
    ) {
        public DesktopHintResponse {
            summary = text(summary);
            hints = list(hints);
            aiSuggestion = text(aiSuggestion);
            metadata = safeMap(metadata);
        }
    }

    public record DesktopHint(
            String type,
            String title,
            String message,
            String fieldId,
            String severity,
            double confidence,
            Map<String, Object> metadata
    ) {
        public DesktopHint {
            type = textOr(type, "hint");
            title = text(title);
            message = text(message);
            fieldId = text(fieldId);
            severity = textOr(severity, "info");
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            metadata = safeMap(metadata);
        }
    }

    public record DesktopFormFillRequest(
            DesktopFocusContext context,
            String userGoal,
            String locale,
            Map<String, Object> profile,
            Map<String, Object> constraints,
            boolean allowSensitiveSuggestions
    ) {
        public DesktopFormFillRequest {
            context = context == null ? DesktopFocusContext.empty() : context;
            userGoal = text(userGoal);
            locale = textOr(locale, "en-US");
            profile = safeMap(profile);
            constraints = safeMap(constraints);
        }
    }

    public record DesktopFormFillPlan(
            boolean ok,
            String summary,
            List<DesktopFieldPlan> fields,
            List<String> warnings,
            boolean requiresApproval,
            String aiSuggestion,
            Map<String, Object> metadata
    ) {
        public DesktopFormFillPlan {
            summary = text(summary);
            fields = list(fields);
            warnings = list(warnings);
            aiSuggestion = text(aiSuggestion);
            metadata = safeMap(metadata);
        }
    }

    public record DesktopFieldPlan(
            String fieldId,
            String label,
            String action,
            String value,
            double confidence,
            String reason,
            boolean sensitive,
            boolean needsUserReview,
            Map<String, Object> metadata
    ) {
        public DesktopFieldPlan {
            fieldId = text(fieldId);
            label = text(label);
            action = textOr(action, "leave");
            value = text(value);
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            reason = text(reason);
            metadata = safeMap(metadata);
        }
    }

    public record DesktopContextAnalysis(
            boolean ok,
            String summary,
            String riskLevel,
            int fieldCount,
            int requiredFieldCount,
            int sensitiveFieldCount,
            String focusedFieldId,
            List<String> recommendedNextActions,
            List<String> warnings,
            Map<String, Object> metadata
    ) {
        public DesktopContextAnalysis {
            summary = text(summary);
            riskLevel = textOr(riskLevel, "low");
            focusedFieldId = text(focusedFieldId);
            recommendedNextActions = list(recommendedNextActions);
            warnings = list(warnings);
            metadata = safeMap(metadata);
        }
    }

    static String text(String value) {
        return value == null ? "" : value.trim();
    }

    static String textOr(String value, String fallback) {
        String normalized = text(value);
        return normalized.isBlank() ? fallback : normalized;
    }

    static <T> List<T> list(List<T> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    static Map<String, Object> safeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            safe.put(entry.getKey(), safeValue(entry.getValue()));
        }
        return Map.copyOf(safe);
    }

    @SuppressWarnings("unchecked")
    private static Object safeValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    nested.put(String.valueOf(entry.getKey()), safeValue(entry.getValue()));
                }
            }
            return Map.copyOf(nested);
        }
        if (value instanceof List<?> values) {
            ArrayList<Object> nested = new ArrayList<>();
            for (Object item : values) {
                nested.add(safeValue(item));
            }
            return List.copyOf(nested);
        }
        return value;
    }
}
