package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomField;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomForm;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomIngestResult;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomSnapshotRequest;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomStatus;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomSubmitControl;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshotResult;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class BrowserDomService {
    public static final String SOURCE = "browser-dom-extension";

    private final BrowserDomProperties properties;
    private final DesktopSnapshotIngestor snapshotIngestor;
    private final DesktopSnapshotConsumer snapshotConsumer;
    private final OperatorLogService logService;
    private final AtomicLong receivedSnapshots = new AtomicLong();
    private final AtomicLong acceptedSnapshots = new AtomicLong();
    private final AtomicLong rejectedSnapshots = new AtomicLong();

    private volatile Instant lastReceivedAt;
    private volatile String lastUrl = "";
    private volatile String lastOrigin = "";
    private volatile String lastCode = "idle";
    private volatile String lastMessage = "Browser DOM snapshot has not been received yet.";
    private volatile int lastFormCount;
    private volatile int lastFieldCount;

    public BrowserDomService(
            BrowserDomProperties properties,
            DesktopSnapshotIngestor snapshotIngestor,
            DesktopSnapshotConsumer snapshotConsumer,
            OperatorLogService logService
    ) {
        this.properties = properties;
        this.snapshotIngestor = snapshotIngestor;
        this.snapshotConsumer = snapshotConsumer;
        this.logService = logService;
    }

    public BrowserDomIngestResult ingest(BrowserDomSnapshotRequest request, String suppliedToken, String requestOrigin) {
        receivedSnapshots.incrementAndGet();
        lastReceivedAt = Instant.now();
        lastOrigin = text(requestOrigin);

        if (!properties.isEnabled()) {
            return reject("browser_dom_disabled", "Browser DOM recognition is disabled.", List.of("suite.desktop-helper.browser-dom.enabled=false"), Map.of());
        }
        BrowserDomIngestResult authorizationFailure = authorize(suppliedToken);
        if (authorizationFailure != null) {
            return authorizationFailure;
        }
        if (request == null) {
            return reject("browser_dom_payload_missing", "Browser DOM snapshot payload is missing.", List.of(), Map.of());
        }
        if (!Boolean.TRUE.equals(request.metadata().get("activeTab"))
                || !Boolean.TRUE.equals(request.metadata().get("windowFocused"))) {
            return reject(
                    "browser_dom_inactive_tab",
                    "Browser DOM snapshot was ignored because it did not come from the active tab in the focused browser window.",
                    List.of("Reload SpringSuite Form Bridge 0.3.1 or newer."),
                    Map.of("pageId", request.pageId(), "url", request.url())
            );
        }
        if (properties.getMaxForms() > 0 && request.forms().size() > properties.getMaxForms()) {
            return reject(
                    "browser_dom_form_limit",
                    "Browser snapshot contains too many forms.",
                    List.of("Maximum accepted form count is " + properties.getMaxForms() + "."),
                    Map.of("receivedForms", request.forms().size())
            );
        }

        URI pageUri;
        try {
            pageUri = sanitizeUri(validatePageUri(request.url()));
        } catch (IllegalArgumentException ex) {
            return reject("browser_dom_url_invalid", ex.getMessage(), List.of(), Map.of());
        }

        Instant capturedAt;
        try {
            capturedAt = request.capturedAt().isBlank() ? Instant.now() : Instant.parse(request.capturedAt());
        } catch (DateTimeParseException ex) {
            return reject("browser_dom_timestamp_invalid", "capturedAt must be an ISO-8601 instant.", List.of(), Map.of("capturedAt", request.capturedAt()));
        }
        Instant now = Instant.now();
        if (capturedAt.isBefore(now.minus(properties.getMaxSnapshotAge()))) {
            return reject(
                    "browser_dom_snapshot_stale",
                    "Browser DOM snapshot is older than the configured acceptance window.",
                    List.of("Capture a fresh page snapshot."),
                    Map.of("capturedAt", capturedAt, "maxSnapshotAge", properties.getMaxSnapshotAge().toString())
            );
        }
        if (capturedAt.isAfter(now.plus(properties.getMaxFutureSkew()))) {
            return reject(
                    "browser_dom_snapshot_future",
                    "Browser DOM snapshot timestamp is too far in the future.",
                    List.of("Check browser and SpringSuite system clocks."),
                    Map.of("capturedAt", capturedAt, "maxFutureSkew", properties.getMaxFutureSkew().toString())
            );
        }

        BrowserDomForm activeForm = selectActiveForm(request.forms());
        if (activeForm == null) {
            return reject("browser_dom_form_missing", "No web form with recognizable controls was supplied.", List.of(), Map.of("formCount", request.forms().size()));
        }

        ArrayList<String> warnings = new ArrayList<>();
        List<Map<String, Object>> fields = normalizeFields(activeForm, warnings);
        if (fields.isEmpty()) {
            return reject(
                    "browser_dom_fields_missing",
                    "The selected web form contains no visible recognizable fields.",
                    warnings,
                    Map.of("formId", activeForm.id(), "formName", activeForm.name())
            );
        }

        LinkedHashMap<String, Object> form = new LinkedHashMap<>();
        form.put("id", firstText(activeForm.id(), "dom:form-1"));
        form.put("name", firstText(activeForm.name(), pageUri.getHost(), "Web form"));
        form.put("action", resolveAction(pageUri, activeForm.action()));
        form.put("method", normalizeMethod(activeForm.method()));
        form.put("fields", fields);
        form.put("browserDom", true);
        form.put("source", SOURCE);
        form.put("submitControls", normalizeSubmitControls(activeForm.submitControls()));
        LinkedHashMap<String, Object> formMetadata = allowedMetadata(
                activeForm.metadata(),
                List.of("cssSelector", "autocomplete", "encoding", "target", "noValidate", "synthetic", "reason")
        );
        formMetadata.put("browserDom", true);
        formMetadata.put("source", SOURCE);
        formMetadata.put("active", activeForm.active());
        formMetadata.put("submitControls", normalizeSubmitControls(activeForm.submitControls()));
        formMetadata.put("pageFormCount", request.forms().size());
        form.put("metadata", formMetadata);

        BrowserDomField focused = activeForm.fields().stream()
                .filter(field -> field != null && field.focused())
                .findFirst()
                .orElse(null);
        LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
        raw.put("schema", "spring-suite.desktop_inspection.v1");
        raw.put("source", SOURCE);
        raw.put("capturedAt", capturedAt.toString());
        raw.put("platform", "web");
        raw.put("activeApplication", firstText(request.browser(), "web-browser"));
        raw.put("activeWindowTitle", firstText(request.title(), pageUri.getHost()));
        raw.put("url", pageUri.toString());
        raw.put("focusedElementRole", focused == null ? "" : firstText(focused.role(), roleForType(focused.type())));
        raw.put("focusedElementName", focused == null ? "" : firstText(focused.label(), focused.name(), focused.id()));
        raw.put("screenText", screenText(request.title(), activeForm, fields));
        raw.put("form", form);

        LinkedHashMap<String, Object> rawMetadata = allowedMetadata(
                request.metadata(),
                List.of("generator", "generatorVersion", "origin", "path", "formCount", "viewport")
        );
        rawMetadata.put("browserDom", true);
        rawMetadata.put("pageId", request.pageId());
        rawMetadata.put("language", request.language());
        rawMetadata.put("activeElementSelector", request.activeElementSelector());
        rawMetadata.put("origin", originOf(pageUri));
        rawMetadata.put("requestOrigin", lastOrigin);
        rawMetadata.put("formCount", request.forms().size());
        rawMetadata.put("fieldCount", fields.size());
        rawMetadata.put("formSummaries", summarizeForms(request.forms()));
        raw.put("metadata", rawMetadata);

        LinkedHashMap<String, Object> ingestBody = new LinkedHashMap<>();
        ingestBody.put("source", SOURCE);
        ingestBody.put("store", true);
        ingestBody.put("snapshot", raw);
        DesktopSnapshotResult snapshotResult = snapshotIngestor.ingest(ingestBody);
        if (!snapshotResult.ok() || snapshotResult.snapshot() == null) {
            return reject(
                    firstText(snapshotResult.code(), "browser_dom_ingest_failed"),
                    firstText(snapshotResult.message(), "Browser DOM snapshot could not be normalized."),
                    mergeWarnings(warnings, snapshotResult.warnings()),
                    snapshotResult.metadata()
            );
        }

        acceptedSnapshots.incrementAndGet();
        lastUrl = pageUri.toString();
        lastFormCount = request.forms().size();
        lastFieldCount = fields.size();
        updateState("ok", "Recognized web form with " + fields.size() + " field(s).");
        snapshotConsumer.acceptSnapshot(snapshotResult.snapshot());

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("selectedFormId", activeForm.id());
        metadata.put("selectedFormName", activeForm.name());
        metadata.put("pageOrigin", originOf(pageUri));
        metadata.put("formCount", request.forms().size());
        metadata.put("fieldCount", fields.size());
        metadata.put("snapshotSource", SOURCE);

        logService.append(OperatorLogLevel.INFO, SOURCE, "browser DOM form recognized", Map.of(
                "url", pageUri.toString(),
                "forms", request.forms().size(),
                "fields", fields.size(),
                "snapshotId", snapshotResult.snapshot().snapshotId()
        ));
        return new BrowserDomIngestResult(
                true,
                "ok",
                lastMessage,
                snapshotResult.snapshot(),
                status(),
                mergeWarnings(warnings, snapshotResult.warnings()),
                metadata
        );
    }

    public boolean isAuthorized(String suppliedToken) {
        if (!properties.tokenRequired()) {
            return true;
        }
        if (properties.getToken().isBlank()) {
            return false;
        }
        byte[] expected = properties.getToken().getBytes(StandardCharsets.UTF_8);
        byte[] actual = text(suppliedToken).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    public BrowserDomStatus status() {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("endpoint", properties.getEndpointPath());
        metadata.put("commandNextEndpoint", BrowserDomProperties.COMMAND_NEXT_ENDPOINT);
        metadata.put("commandAckEndpoint", BrowserDomProperties.COMMAND_ACK_ENDPOINT);
        metadata.put("writeEnabled", properties.isWriteEnabled());
        metadata.put("preserveExistingValues", properties.isPreserveExistingValues());
        metadata.put("commandTtl", properties.getCommandTtl().toString());
        metadata.put("submitEnabled", false);
        metadata.put("maxSnapshotAge", properties.getMaxSnapshotAge().toString());
        metadata.put("maxForms", properties.getMaxForms());
        metadata.put("maxFieldsPerForm", properties.getMaxFieldsPerForm());
        metadata.put("maxOptionsPerField", properties.getMaxOptionsPerField());
        metadata.put("allowedSchemes", properties.getAllowedSchemes());
        metadata.put("source", SOURCE);
        return new BrowserDomStatus(
                properties.isEnabled(),
                properties.tokenRequired(),
                lastReceivedAt,
                receivedSnapshots.get(),
                acceptedSnapshots.get(),
                rejectedSnapshots.get(),
                lastUrl,
                lastOrigin,
                lastCode,
                lastMessage,
                lastFormCount,
                lastFieldCount,
                metadata
        );
    }

    private BrowserDomIngestResult authorize(String suppliedToken) {
        if (!properties.tokenRequired()) {
            return null;
        }
        if (properties.getToken().isBlank()) {
            return reject(
                    "browser_dom_token_unconfigured",
                    "Browser DOM token is required but no server token is configured.",
                    List.of("Set suite.desktop-helper.browser-dom.token or SPRINGSUITE_BROWSER_DOM_TOKEN."),
                    Map.of()
            );
        }
        if (!isAuthorized(suppliedToken)) {
            return reject("browser_dom_unauthorized", "Browser DOM token is invalid.", List.of(), Map.of());
        }
        return null;
    }

    private URI validatePageUri(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Browser DOM snapshot URL is missing.");
        }
        try {
            URI uri = new URI(rawUrl.trim());
            String scheme = text(uri.getScheme()).toLowerCase(Locale.ROOT);
            if (!uri.isAbsolute() || !properties.getAllowedSchemes().contains(scheme)) {
                throw new IllegalArgumentException("Browser DOM snapshot URL scheme is not allowed: " + firstText(scheme, "missing"));
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Browser DOM snapshot URL must contain a valid host.");
            }
            return uri;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Browser DOM snapshot URL is invalid.");
        }
    }

    private BrowserDomForm selectActiveForm(List<BrowserDomForm> forms) {
        if (forms == null || forms.isEmpty()) {
            return null;
        }
        return forms.stream()
                .filter(form -> form != null && form.fields() != null && !form.fields().isEmpty())
                .max(Comparator.comparingInt(this::formScore))
                .orElse(null);
    }

    private int formScore(BrowserDomForm form) {
        int score = form.active() ? 10_000 : 0;
        score += form.fields().stream().anyMatch(field -> field != null && field.focused()) ? 5_000 : 0;
        score += (int) form.fields().stream().filter(field -> field != null && field.visible()).count() * 10;
        score += (int) form.fields().stream().filter(field -> field != null && field.required()).count();
        return score;
    }

    private List<Map<String, Object>> normalizeFields(BrowserDomForm form, List<String> warnings) {
        int limit = properties.getMaxFieldsPerForm();
        if (limit > 0 && form.fields().size() > limit) {
            warnings.add("Web form contains " + form.fields().size() + " fields; only the first " + limit + " are accepted.");
        }
        ArrayList<Map<String, Object>> result = new ArrayList<>();
        int ordinal = 0;
        for (BrowserDomField field : form.fields()) {
            if (field == null || (limit > 0 && result.size() >= limit)) {
                break;
            }
            String type = normalizeFieldType(field.type());
            if ("hidden".equals(type) || (!field.visible() && !field.focused())) {
                continue;
            }
            ordinal++;
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("id", firstText(field.id(), "dom:field-" + ordinal));
            normalized.put("label", firstText(field.label(), field.name(), field.placeholder(), "Web field " + ordinal));
            normalized.put("name", firstText(field.name(), field.id(), "field-" + ordinal));
            normalized.put("type", type);
            normalized.put("role", firstText(field.role(), roleForType(type)));
            normalized.put("valuePresent", field.valuePresent());
            normalized.put("placeholder", field.placeholder());
            normalized.put("required", field.required());
            normalized.put("focused", field.focused());
            normalized.put("sensitive", field.sensitive() || sensitiveType(type));
            normalized.put("readOnly", field.readOnly());
            normalized.put("disabled", field.disabled());
            normalized.put("visible", field.visible());
            normalized.put("options", properties.getMaxOptionsPerField() > 0
                    ? field.options().stream().limit(properties.getMaxOptionsPerField()).toList()
                    : List.copyOf(field.options()));
            Object bounds = field.metadata().get("bounds");
            if (bounds != null) {
                normalized.put("bounds", bounds);
            }
            normalized.put("cssSelector", field.metadata().getOrDefault("cssSelector", ""));
            LinkedHashMap<String, Object> metadata = allowedMetadata(
                    field.metadata(),
                    List.of(
                            "cssSelector", "formSelector", "tagName", "autocomplete", "inputMode",
                            "min", "max", "step", "pattern", "minLength", "maxLength", "multiple",
                            "contextPrompt", "promptSource", "bounds"
                    )
            );
            metadata.put("browserDom", true);
            metadata.put("source", SOURCE);
            metadata.put("readOnly", field.readOnly());
            metadata.put("disabled", field.disabled());
            metadata.put("visible", field.visible());
            metadata.put("valuePresent", field.valuePresent());
            metadata.put("role", field.role());
            normalized.put("metadata", metadata);
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> normalizeSubmitControls(List<BrowserDomSubmitControl> controls) {
        if (controls == null || controls.isEmpty()) {
            return List.of();
        }
        return controls.stream().filter(control -> control != null).map(control -> {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("id", control.id());
            value.put("label", control.label());
            value.put("type", control.type());
            value.put("disabled", control.disabled());
            value.put("metadata", allowedMetadata(control.metadata(), List.of("cssSelector", "tagName", "bounds")));
            return Map.<String, Object>copyOf(value);
        }).toList();
    }

    private List<Map<String, Object>> summarizeForms(List<BrowserDomForm> forms) {
        java.util.stream.Stream<BrowserDomForm> stream = forms.stream().filter(form -> form != null);
        if (properties.getMaxForms() > 0) {
            stream = stream.limit(properties.getMaxForms());
        }
        return stream.map(form -> {
            LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", form.id());
            summary.put("name", form.name());
            summary.put("method", normalizeMethod(form.method()));
            summary.put("active", form.active());
            summary.put("fieldCount", form.fields().size());
            summary.put("submitControlCount", form.submitControls().size());
            return Map.<String, Object>copyOf(summary);
        }).toList();
    }

    private String resolveAction(URI pageUri, String rawAction) {
        if (rawAction == null || rawAction.isBlank()) {
            return pageUri.toString();
        }
        try {
            return sanitizeUri(pageUri.resolve(rawAction.trim())).toString();
        } catch (IllegalArgumentException ignored) {
            return pageUri.toString();
        }
    }

    private String normalizeMethod(String method) {
        String normalized = text(method).toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "post", "dialog" -> normalized;
            default -> "get";
        };
    }

    private String normalizeFieldType(String type) {
        String normalized = text(type).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "text";
        }
        return switch (normalized) {
            case "select-one", "select-multiple", "combobox", "listbox" -> "select";
            case "textarea" -> "textarea";
            case "tel" -> "telephone";
            default -> normalized;
        };
    }

    private String roleForType(String type) {
        return switch (normalizeFieldType(type)) {
            case "select" -> "combobox";
            case "checkbox" -> "checkbox";
            case "radio" -> "radio";
            case "range" -> "slider";
            case "button", "submit", "reset" -> "button";
            default -> "textbox";
        };
    }

    private boolean sensitiveType(String type) {
        String normalized = normalizeFieldType(type);
        return "password".equals(normalized) || "hidden".equals(normalized);
    }

    private String screenText(String title, BrowserDomForm form, List<Map<String, Object>> fields) {
        StringBuilder text = new StringBuilder(firstText(title, form.name(), "Web form"));
        for (Map<String, Object> field : fields) {
            String label = text(field.get("label"));
            if (!label.isBlank()) {
                text.append('\n').append(label);
            }
            if (text.length() >= 6_000) {
                break;
            }
        }
        return text.length() <= 6_000 ? text.toString() : text.substring(0, 6_000);
    }

    private URI sanitizeUri(URI uri) {
        try {
            return new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    firstText(uri.getPath(), "/"),
                    null,
                    null
            );
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Browser DOM URL could not be sanitized.");
        }
    }

    private LinkedHashMap<String, Object> allowedMetadata(Map<String, Object> source, List<String> allowedKeys) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return result;
        }
        for (String key : allowedKeys) {
            Object value = source.get(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    private String originOf(URI uri) {
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port);
    }

    private BrowserDomIngestResult reject(String code, String message, List<String> warnings, Map<String, Object> metadata) {
        rejectedSnapshots.incrementAndGet();
        updateState(code, message);
        logService.append(OperatorLogLevel.WARN, SOURCE, "browser DOM snapshot rejected", Map.of(
                "code", code,
                "message", message,
                "origin", lastOrigin
        ));
        return BrowserDomIngestResult.failed(code, message, status(), warnings, metadata);
    }

    private List<String> mergeWarnings(List<String> left, List<String> right) {
        ArrayList<String> merged = new ArrayList<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return List.copyOf(merged);
    }

    private void updateState(String code, String message) {
        lastCode = text(code);
        lastMessage = text(message);
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
        return value == null ? "" : String.valueOf(value).trim();
    }
}
