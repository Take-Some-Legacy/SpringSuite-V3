package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormField;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * Single-active-form relay between the desktop overlay and an operator-driven ChatGPT MCP session.
 * Existing field values and secrets never leave SpringSuite through this service.
 */
@Service
public class ChatGptFormRelayService implements DesktopFormRelay {
    public static final String SOURCE_ID = "chatgpt-5.6";
    private static final String SCHEMA = "spring-suite.chatgpt_form_relay.v1";
    private static final Duration RELAY_TTL = Duration.ofMinutes(15);
    private static final int MAX_VALUE_CHARS = 4_000;

    private final AtomicLong revisions = new AtomicLong();
    private RelayState current;

    public synchronized Map<String, Object> publish(String signature, DesktopSnapshot snapshot, String locale) {
        Instant now = Instant.now();
        RelayState active = activeState(now);
        if (active != null && active.signature().equals(text(signature))
                && !"consumed".equals(active.status()) && !"cancelled".equals(active.status())) {
            current = new RelayState(
                    active.relayId(), active.signature(), snapshot, normalizedLocale(locale),
                    active.createdAt(), now.plus(RELAY_TTL), active.revision(), active.status(),
                    active.values(), active.summary(), active.metadata()
            );
            return view(current, true);
        }

        current = new RelayState(
                UUID.randomUUID().toString(),
                text(signature),
                snapshot,
                normalizedLocale(locale),
                now,
                now.plus(RELAY_TTL),
                revisions.incrementAndGet(),
                "waiting",
                Map.of(),
                "",
                Map.of()
        );
        return view(current, true);
    }

    @Override
    public synchronized Map<String, Object> currentRequest() {
        RelayState state = activeState(Instant.now());
        if (state == null) {
            return idle("No active form is waiting for ChatGPT 5.6.");
        }
        return view(state, true);
    }

    @Override
    public synchronized Map<String, Object> status(String relayId) {
        RelayState state = activeState(Instant.now());
        if (state == null) {
            return idle("No active relay request.");
        }
        if (!text(relayId).isBlank() && !state.relayId().equals(text(relayId))) {
            return idle("The requested relay id is no longer active.");
        }
        return view(state, false);
    }

    @Override
    public synchronized Map<String, Object> submit(Map<String, Object> arguments) {
        RelayState state = activeState(Instant.now());
        if (state == null) {
            return result(false, "relay_missing", "No active form relay request.", null, List.of());
        }
        String relayId = text(arguments == null ? null : arguments.get("relayId"));
        if (relayId.isBlank() || !state.relayId().equals(relayId)) {
            return result(false, "relay_mismatch", "relayId does not match the active form.", state, List.of());
        }
        if (state.snapshot() == null || state.snapshot().context() == null) {
            return result(false, "relay_snapshot_missing", "The active relay has no form snapshot.", state, List.of());
        }

        Map<String, DesktopFormField> allowed = fieldsByRelayId(state.snapshot());
        LinkedHashMap<String, String> accepted = new LinkedHashMap<>();
        ArrayList<String> warnings = new ArrayList<>();

        Object rawFields = arguments == null ? null : arguments.get("fields");
        if (rawFields instanceof List<?> list) {
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> item)) {
                    warnings.add("Ignored a non-object fields entry.");
                    continue;
                }
                acceptValue(allowed, accepted, warnings, text(item.get("fieldId")), text(item.get("value")));
            }
        }
        Object rawValues = arguments == null ? null : arguments.get("values");
        if (rawValues instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                acceptValue(allowed, accepted, warnings, text(entry.getKey()), text(entry.getValue()));
            }
        }

        if (accepted.isEmpty()) {
            return result(false, "relay_no_safe_values",
                    "No submitted value passed the local field and safety checks.", state, warnings);
        }

        String summary = text(arguments == null ? null : arguments.get("summary"));
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("submittedBy", "chatgpt-mcp");
        metadata.put("submittedAt", Instant.now().toString());
        metadata.put("acceptedValueCount", accepted.size());
        metadata.put("warningCount", warnings.size());

        current = new RelayState(
                state.relayId(), state.signature(), state.snapshot(), state.locale(),
                state.createdAt(), Instant.now().plus(RELAY_TTL), revisions.incrementAndGet(), "ready",
                Map.copyOf(accepted), summary, Map.copyOf(metadata)
        );
        return result(true, "relay_ready",
                "ChatGPT 5.6 draft accepted for operator review. No fields were written.", current, warnings);
    }

    public synchronized Optional<RelayResult> readyFor(String signature) {
        RelayState state = activeState(Instant.now());
        if (state == null || !state.signature().equals(text(signature)) || !"ready".equals(state.status())) {
            return Optional.empty();
        }
        return Optional.of(new RelayResult(
                state.relayId(), state.signature(), state.revision(), state.values(), state.summary(), state.metadata()
        ));
    }

    public synchronized long revisionFor(String signature) {
        RelayState state = activeState(Instant.now());
        return state != null && state.signature().equals(text(signature)) ? state.revision() : 0L;
    }

    public synchronized void cancel(String signature, String reason) {
        RelayState state = activeState(Instant.now());
        if (state == null || !state.signature().equals(text(signature))) {
            return;
        }
        current = new RelayState(
                state.relayId(), state.signature(), state.snapshot(), state.locale(), state.createdAt(),
                Instant.now().plusSeconds(30), revisions.incrementAndGet(), "cancelled", Map.of(),
                text(reason), state.metadata()
        );
    }

    public synchronized void markConsumed(String signature) {
        RelayState state = activeState(Instant.now());
        if (state == null || !state.signature().equals(text(signature))) {
            return;
        }
        current = new RelayState(
                state.relayId(), state.signature(), state.snapshot(), state.locale(), state.createdAt(),
                Instant.now().plusSeconds(30), revisions.incrementAndGet(), "consumed", Map.of(),
                "The operator accepted and executed the relay plan.", state.metadata()
        );
    }

    private RelayState activeState(Instant now) {
        RelayState state = current;
        if (state == null) {
            return null;
        }
        if (now.isAfter(state.expiresAt())) {
            current = null;
            return null;
        }
        return state;
    }

    private void acceptValue(
            Map<String, DesktopFormField> allowed,
            Map<String, String> accepted,
            List<String> warnings,
            String fieldId,
            String value
    ) {
        if (fieldId.isBlank() || value.isBlank()) {
            warnings.add("Ignored an entry with an empty fieldId or value.");
            return;
        }
        DesktopFormField field = allowed.get(fieldId);
        if (field == null) {
            warnings.add("Unknown fieldId ignored: " + fieldId);
            return;
        }
        if (isSensitive(field)) {
            warnings.add("Sensitive field rejected: " + fieldId);
            return;
        }
        if (fieldHasValue(field)) {
            warnings.add("Already-filled field rejected: " + fieldId);
            return;
        }
        if (!isAvailable(field)) {
            warnings.add("Unavailable field rejected: " + fieldId);
            return;
        }
        if (value.length() > MAX_VALUE_CHARS) {
            warnings.add("Value is too long and was rejected: " + fieldId);
            return;
        }
        if (!field.options().isEmpty() && field.options().stream().noneMatch(value::equals)) {
            warnings.add("Value does not match an allowed option: " + fieldId);
            return;
        }
        accepted.put(fieldId, value);
    }

    private Map<String, DesktopFormField> fieldsByRelayId(DesktopSnapshot snapshot) {
        LinkedHashMap<String, DesktopFormField> fields = new LinkedHashMap<>();
        List<DesktopFormField> formFields = snapshot.context().form().fields();
        for (int index = 0; index < formFields.size(); index++) {
            DesktopFormField field = formFields.get(index);
            fields.putIfAbsent(relayFieldId(field, index), field);
        }
        return fields;
    }

    private Map<String, Object> view(RelayState state, boolean includeFields) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("schema", SCHEMA);
        out.put("available", true);
        out.put("relayId", state.relayId());
        out.put("signature", state.signature());
        out.put("status", state.status());
        out.put("revision", state.revision());
        out.put("createdAt", state.createdAt().toString());
        out.put("expiresAt", state.expiresAt().toString());
        out.put("locale", state.locale());
        out.put("summary", state.summary());
        out.put("valueCount", state.values().size());
        out.put("instructions", "Return only ordinary non-sensitive draft values. Use desktop.form.relay.submit; SpringSuite will validate and wait for the operator to press Fill.");
        if (state.snapshot() != null && state.snapshot().context() != null) {
            LinkedHashMap<String, Object> page = new LinkedHashMap<>();
            page.put("application", state.snapshot().context().activeApplication());
            page.put("title", state.snapshot().context().activeWindowTitle());
            page.put("url", state.snapshot().context().url());
            page.put("formId", state.snapshot().context().form().id());
            page.put("formName", state.snapshot().context().form().name());
            out.put("page", page);
            if (includeFields) {
                out.put("fields", relayFields(state.snapshot()));
            }
        }
        out.put("metadata", state.metadata());
        return out;
    }

    private List<Map<String, Object>> relayFields(DesktopSnapshot snapshot) {
        ArrayList<Map<String, Object>> fields = new ArrayList<>();
        List<DesktopFormField> formFields = snapshot.context().form().fields();
        for (int index = 0; index < formFields.size(); index++) {
            DesktopFormField field = formFields.get(index);
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("fieldId", relayFieldId(field, index));
            item.put("label", field.displayName());
            item.put("name", field.name());
            item.put("type", field.type());
            item.put("required", field.required());
            item.put("focused", field.focused());
            item.put("sensitive", isSensitive(field));
            item.put("valuePresent", fieldHasValue(field));
            item.put("available", isAvailable(field));
            item.put("placeholder", field.placeholder());
            item.put("contextPrompt", text(field.metadata().get("contextPrompt")));
            item.put("options", field.options());
            fields.add(Map.copyOf(item));
        }
        return List.copyOf(fields);
    }

    private String relayFieldId(DesktopFormField field, int index) {
        if (field != null) {
            if (!field.id().isBlank()) {
                return field.id();
            }
            if (!field.name().isBlank()) {
                return field.name();
            }
        }
        return "field-" + (index + 1);
    }

    private boolean isSensitive(DesktopFormField field) {
        if (field == null || field.sensitive()) {
            return true;
        }
        String type = field.type().toLowerCase(Locale.ROOT);
        if (type.equals("password") || type.equals("hidden")) {
            return true;
        }
        String haystack = String.join(" ", field.id(), field.name(), field.label(), field.placeholder(), field.type())
                .toLowerCase(Locale.ROOT);
        return haystack.contains("password") || haystack.contains("passcode")
                || haystack.contains("secret") || haystack.contains("token")
                || haystack.contains("api key") || haystack.contains("apikey")
                || haystack.contains("credit card") || haystack.contains("cvv")
                || haystack.contains("bank account") || haystack.contains("social security");
    }

    private boolean fieldHasValue(DesktopFormField field) {
        if (field == null) {
            return false;
        }
        if (!field.value().isBlank()) {
            return true;
        }
        Object present = field.metadata().get("valuePresent");
        return present instanceof Boolean bool ? bool : present != null && Boolean.parseBoolean(String.valueOf(present));
    }

    private boolean isAvailable(DesktopFormField field) {
        if (field == null) {
            return false;
        }
        return !bool(field.metadata().get("disabled"), false)
                && !bool(field.metadata().get("readOnly"), false)
                && bool(field.metadata().get("visible"), true);
    }

    private boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> idle(String message) {
        return Map.of(
                "schema", SCHEMA,
                "available", false,
                "status", "idle",
                "message", message
        );
    }

    private Map<String, Object> result(boolean ok, String code, String message, RelayState state, List<String> warnings) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("schema", SCHEMA);
        out.put("ok", ok);
        out.put("code", code);
        out.put("message", message);
        out.put("warnings", warnings == null ? List.of() : List.copyOf(warnings));
        if (state != null) {
            out.put("relayId", state.relayId());
            out.put("signature", state.signature());
            out.put("status", state.status());
            out.put("revision", state.revision());
            out.put("valueCount", state.values().size());
        }
        return out;
    }

    private String normalizedLocale(String value) {
        String locale = text(value);
        return locale.isBlank() ? "ru-RU" : locale;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record RelayResult(
            String relayId,
            String signature,
            long revision,
            Map<String, String> profile,
            String summary,
            Map<String, Object> metadata
    ) {
        public RelayResult {
            relayId = relayId == null ? "" : relayId;
            signature = signature == null ? "" : signature;
            profile = profile == null ? Map.of() : Map.copyOf(profile);
            summary = summary == null ? "" : summary;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    private record RelayState(
            String relayId,
            String signature,
            DesktopSnapshot snapshot,
            String locale,
            Instant createdAt,
            Instant expiresAt,
            long revision,
            String status,
            Map<String, String> values,
            String summary,
            Map<String, Object> metadata
    ) {
    }
}
