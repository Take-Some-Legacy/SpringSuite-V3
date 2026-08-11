package com.takesome.springsuite.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.desktop.DesktopBridgeModels.NormalizedDesktopSnapshot;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFocusContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormField;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DesktopContextNormalizer {
    private final ObjectMapper objectMapper;

    public DesktopContextNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizedDesktopSnapshot normalize(String source, Map<String, Object> raw, DesktopFocusContext explicitContext) {
        Map<String, Object> safeRaw = raw == null ? Map.of() : raw;
        ArrayList<String> warnings = new ArrayList<>();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rawKeys", safeRaw.keySet().stream().sorted().toList());

        DesktopFocusContext context = explicitContext;
        if (context == null || isEmptyContext(context)) {
            context = convertContext(valueAsMap(safeRaw.get("context")), warnings);
        }
        if (context == null || isEmptyContext(context)) {
            context = normalizeRawContext(safeRaw, warnings);
        }

        String resolvedSource = firstText(source, text(safeRaw.get("source")), text(safeRaw.get("adapter")), "external");
        Instant capturedAt = parseInstant(firstText(
                text(safeRaw.get("capturedAt")),
                text(safeRaw.get("timestamp")),
                text(safeRaw.get("time")),
                ""
        ));

        if (context.form().fields().isEmpty() && !context.screenText().isBlank()) {
            warnings.add("Snapshot has visible text but no structured form fields; browser bridge or accessibility bridge should supply DesktopFormContext for accurate filling.");
        }
        if (context.activeApplication().isBlank() && context.activeWindowTitle().isBlank()) {
            warnings.add("Snapshot did not include active window process/title metadata.");
        }

        metadata.put("fieldCount", context.form().fields().size());
        metadata.put("hasScreenText", !context.screenText().isBlank());
        metadata.put("hasSelectedText", !context.selectedText().isBlank());
        return new NormalizedDesktopSnapshot(resolvedSource, capturedAt, context, warnings, metadata);
    }

    private DesktopFocusContext normalizeRawContext(Map<String, Object> raw, List<String> warnings) {
        Map<String, Object> activeWindow = firstMap(raw, "activeWindow", "window", "foregroundWindow");
        Map<String, Object> focusedElement = firstMap(raw, "focusedElement", "focus", "focusedControl", "control");
        Map<String, Object> screenText = firstMap(raw, "screenText", "text", "ocr");
        Map<String, Object> clipboard = firstMap(raw, "clipboard", "clipboardState");

        DesktopFormContext form = normalizeForm(raw, warnings);

        String platform = firstText(
                text(raw.get("platform")),
                text(raw.get("os")),
                text(raw.get("operatingSystem")),
                ""
        );
        String activeApplication = firstText(
                text(raw.get("activeApplication")),
                text(raw.get("application")),
                text(raw.get("process")),
                text(activeWindow.get("process")),
                text(activeWindow.get("processName")),
                text(activeWindow.get("app")),
                ""
        );
        String activeWindowTitle = firstText(
                text(raw.get("activeWindowTitle")),
                text(raw.get("windowTitle")),
                text(raw.get("title")),
                text(activeWindow.get("title")),
                text(activeWindow.get("name")),
                ""
        );
        String url = firstText(
                text(raw.get("url")),
                text(raw.get("uri")),
                text(activeWindow.get("url")),
                text(activeWindow.get("uri")),
                text(activeWindow.get("location")),
                ""
        );
        String focusedRole = firstText(
                text(raw.get("focusedElementRole")),
                text(focusedElement.get("role")),
                text(focusedElement.get("controlType")),
                text(focusedElement.get("type")),
                ""
        );
        String focusedName = firstText(
                text(raw.get("focusedElementName")),
                text(focusedElement.get("name")),
                text(focusedElement.get("label")),
                text(focusedElement.get("automationId")),
                ""
        );
        String selectedText = firstText(
                text(raw.get("selectedText")),
                text(screenText.get("selectedText")),
                text(screenText.get("selection")),
                ""
        );
        String clipboardPreview = firstText(
                text(raw.get("clipboardPreview")),
                text(clipboard.get("preview")),
                text(clipboard.get("textPreview")),
                ""
        );
        String visibleText = firstText(
                text(raw.get("screenText")),
                text(raw.get("visibleText")),
                text(raw.get("ocrText")),
                text(screenText.get("visibleText")),
                text(screenText.get("text")),
                text(screenText.get("ocrText")),
                ""
        );

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.putAll(valueAsMap(raw.get("metadata")));
        putIfPresent(metadata, "snapshotId", raw.get("snapshotId"));
        putIfPresent(metadata, "captureId", raw.get("captureId"));
        putIfPresent(metadata, "source", raw.get("source"));
        putIfPresent(metadata, "focusedElement", focusedElement);
        putIfPresent(metadata, "activeWindow", activeWindow);

        return new DesktopFocusContext(
                platform,
                activeApplication,
                activeWindowTitle,
                url,
                focusedRole,
                focusedName,
                selectedText,
                clipboardPreview,
                visibleText,
                form,
                metadata
        );
    }

    private DesktopFocusContext convertContext(Map<String, Object> context, List<String> warnings) {
        if (context.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.convertValue(context, DesktopFocusContext.class);
        } catch (IllegalArgumentException ex) {
            warnings.add("Provided context could not be mapped directly to DesktopFocusContext; falling back to raw snapshot normalization: " + safeMessage(ex));
            return null;
        }
    }

    private DesktopFormContext normalizeForm(Map<String, Object> raw, List<String> warnings) {
        Map<String, Object> form = firstMap(raw, "form", "activeForm");
        if (form.isEmpty()) {
            List<Map<String, Object>> forms = valueAsMapList(raw.get("forms"));
            if (!forms.isEmpty()) {
                form = forms.get(0);
                if (forms.size() > 1) {
                    warnings.add("Snapshot contains multiple forms; using the first form for DesktopFocusContext v1.");
                }
            }
        }
        if (form.isEmpty()) {
            List<Map<String, Object>> fields = firstFieldList(raw);
            if (!fields.isEmpty()) {
                form = Map.of("fields", fields);
            }
        }
        if (form.isEmpty()) {
            return DesktopFormContext.empty();
        }

        List<DesktopFormField> fields = normalizeFields(firstFieldList(form), warnings);
        LinkedHashMap<String, Object> formMetadata = new LinkedHashMap<>(metadataWithout(form, "fields", "metadata"));
        formMetadata.putAll(valueAsMap(form.get("metadata")));
        return new DesktopFormContext(
                text(form.get("id")),
                firstText(text(form.get("name")), text(form.get("title")), ""),
                firstText(text(form.get("action")), text(form.get("url")), ""),
                text(form.get("method")),
                fields,
                formMetadata
        );
    }

    private List<DesktopFormField> normalizeFields(List<Map<String, Object>> rawFields, List<String> warnings) {
        if (rawFields.isEmpty()) {
            return List.of();
        }
        ArrayList<DesktopFormField> fields = new ArrayList<>();
        for (int i = 0; i < rawFields.size(); i++) {
            Map<String, Object> rawField = rawFields.get(i);
            String id = firstText(text(rawField.get("id")), text(rawField.get("automationId")), text(rawField.get("name")), "field-" + (i + 1));
            String label = firstText(text(rawField.get("label")), text(rawField.get("title")), text(rawField.get("ariaLabel")), text(rawField.get("name")), id);
            String name = firstText(text(rawField.get("name")), text(rawField.get("automationId")), id);
            String type = firstText(text(rawField.get("type")), text(rawField.get("inputType")), text(rawField.get("role")), "text");
            boolean valuePresent = bool(rawField.get("valuePresent"), false) || bool(rawField.get("hasValue"), false);
            String value = valuePresent ? "" : firstText(text(rawField.get("value")), text(rawField.get("text")), "");
            boolean sensitive = bool(rawField.get("sensitive"), false) || isSensitiveType(type) || containsSensitiveHint(id + " " + label + " " + name);
            if (sensitive && !value.isBlank()) {
                value = "";
                warnings.add("Raw value for sensitive field `" + label + "` was redacted during normalization.");
            }

            LinkedHashMap<String, Object> fieldMetadata = new LinkedHashMap<>(metadataWithout(
                    rawField,
                    "value", "text", "options", "choices", "items", "metadata"
            ));
            fieldMetadata.putAll(valueAsMap(rawField.get("metadata")));
            fields.add(new DesktopFormField(
                    id,
                    label,
                    name,
                    type,
                    value,
                    firstText(text(rawField.get("placeholder")), text(rawField.get("hint")), ""),
                    bool(rawField.get("required"), false),
                    bool(rawField.get("focused"), false),
                    sensitive,
                    valueAsStringList(firstNonNull(rawField.get("options"), rawField.get("choices"), rawField.get("items"))),
                    fieldMetadata
            ));
        }
        return fields;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> valueAsMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return normalized;
        }
        return Map.of();
    }

    private List<Map<String, Object>> valueAsMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = valueAsMap(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    private List<String> valueAsStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        ArrayList<String> result = new ArrayList<>();
        for (Object item : list) {
            String text = text(item);
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private List<Map<String, Object>> firstFieldList(Map<String, Object> source) {
        for (String key : List.of("fields", "controls", "inputs", "elements")) {
            List<Map<String, Object>> fields = valueAsMapList(source.get(key));
            if (!fields.isEmpty()) {
                return fields;
            }
        }
        return List.of();
    }

    private Map<String, Object> firstMap(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Map<String, Object> map = valueAsMap(source.get(key));
            if (!map.isEmpty()) {
                return map;
            }
        }
        return Map.of();
    }

    private Map<String, Object> metadataWithout(Map<String, Object> source, String... excludedKeys) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        List<String> excluded = List.of(excludedKeys);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (!excluded.contains(entry.getKey())) {
                metadata.put(entry.getKey(), entry.getValue());
            }
        }
        return DesktopHelperModels.safeMap(metadata);
    }

    private boolean isEmptyContext(DesktopFocusContext context) {
        return context.platform().isBlank()
                && context.activeApplication().isBlank()
                && context.activeWindowTitle().isBlank()
                && context.url().isBlank()
                && context.screenText().isBlank()
                && context.selectedText().isBlank()
                && context.form().fields().isEmpty();
    }

    private boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "true", "1", "yes", "y", "on" -> true;
                case "false", "0", "no", "n", "off" -> false;
                default -> fallback;
            };
        }
        return fallback;
    }

    private boolean isSensitiveType(String type) {
        String normalized = lower(type);
        return normalized.equals("password") || normalized.equals("hidden") || normalized.contains("secret") || normalized.contains("token");
    }

    private boolean containsSensitiveHint(String value) {
        String normalized = lower(value);
        return normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("api key")
                || normalized.contains("card")
                || normalized.contains("cvv")
                || normalized.contains("iban")
                || normalized.contains("passport")
                || normalized.contains("ssn");
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return Instant.now();
        }
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text.trim();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value).trim();
        }
        return "";
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return;
        }
        target.put(key, value);
    }

    private String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
