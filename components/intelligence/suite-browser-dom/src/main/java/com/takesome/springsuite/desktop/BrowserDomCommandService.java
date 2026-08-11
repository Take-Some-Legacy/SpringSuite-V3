package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomCommandAckRequest;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomCommandAckResult;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomFillCommand;
import com.takesome.springsuite.desktop.BrowserDomModels.BrowserDomFillField;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormField;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.observability.SuiteTelemetry;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class BrowserDomCommandService {
    private static final String SOURCE = "browser-dom-command";

    private final BrowserDomProperties properties;
    private final OperatorLogService logService;
    private final SuiteTelemetry telemetry;
    private final Map<String, BrowserDomFillCommand> pendingByPageId = new ConcurrentHashMap<>();
    private final Map<String, BrowserDomFillCommand> pendingByCommandId = new ConcurrentHashMap<>();
    private final AtomicLong queuedCount = new AtomicLong();
    private final AtomicLong acknowledgedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();

    public BrowserDomCommandService(
            BrowserDomProperties properties,
            OperatorLogService logService,
            SuiteTelemetry telemetry
    ) {
        this.properties = properties;
        this.logService = logService;
        this.telemetry = telemetry;
        telemetry.registerGauge("browser_dom", "pending_commands", pendingByCommandId::size);
    }

    public BrowserDomFillCommand enqueue(DesktopSnapshot snapshot, List<DesktopApprovedAction> actions) {
        cleanupExpired();
        if (!properties.isWriteEnabled()) {
            throw new IllegalStateException("Browser DOM form insertion is disabled by configuration.");
        }
        if (snapshot == null || snapshot.context() == null) {
            throw new IllegalArgumentException("Browser DOM snapshot is missing.");
        }
        String pageId = text(snapshot.context().metadata().get("pageId"));
        if (pageId.isBlank()) {
            pageId = text(snapshot.metadata().get("pageId"));
        }
        if (pageId.isBlank()) {
            throw new IllegalArgumentException("Browser DOM snapshot does not contain pageId.");
        }
        String pageUrl = snapshot.context().url();
        if (pageUrl.isBlank()) {
            throw new IllegalArgumentException("Browser DOM snapshot does not contain page URL.");
        }

        Map<String, DesktopFormField> fieldsById = new LinkedHashMap<>();
        for (DesktopFormField field : snapshot.context().form().fields()) {
            fieldsById.put(field.id(), field);
            if (!field.name().isBlank()) {
                fieldsById.putIfAbsent(field.name(), field);
            }
        }

        ArrayList<BrowserDomFillField> fillFields = new ArrayList<>();
        for (DesktopApprovedAction action : actions == null ? List.<DesktopApprovedAction>of() : actions) {
            if (action == null || action.sensitive() || action.submit() || action.value().isBlank()) {
                continue;
            }
            DesktopFormField field = fieldsById.get(action.targetFieldId());
            if (field == null) {
                continue;
            }
            String selector = firstText(
                    text(action.metadata().get("cssSelector")),
                    text(field.metadata().get("cssSelector"))
            );
            if (selector.isBlank()) {
                continue;
            }
            if (metadataBoolean(field.metadata().get("disabled"), false)
                    || metadataBoolean(field.metadata().get("readOnly"), false)
                    || !metadataBoolean(field.metadata().get("visible"), true)
                    || metadataBoolean(field.metadata().get("valuePresent"), false)) {
                continue;
            }
            fillFields.add(new BrowserDomFillField(
                    field.id(),
                    firstText(action.label(), field.displayName()),
                    selector,
                    normalizeAction(action.action()),
                    action.value(),
                    field.type(),
                    Map.of(
                            "required", field.required(),
                            "source", "desktop-agent-overlay",
                            "explicitUserGesture", true
                    )
            ));
        }
        if (fillFields.isEmpty()) {
            throw new IllegalArgumentException("No safe browser fields are available for insertion.");
        }

        Instant now = Instant.now();
        BrowserDomFillCommand command = new BrowserDomFillCommand(
                UUID.randomUUID().toString(),
                pageId,
                sanitizePageUrl(pageUrl),
                snapshot.snapshotId(),
                now,
                now.plus(properties.getCommandTtl()),
                properties.isPreserveExistingValues(),
                false,
                List.copyOf(fillFields),
                Map.of(
                        "source", SOURCE,
                        SuiteTelemetry.CORRELATION_ID, correlationId(snapshot),
                        "explicitUserGesture", true,
                        "fieldCount", fillFields.size(),
                        "submitDisabled", true
                )
        );

        BrowserDomFillCommand previous = pendingByPageId.put(pageId, command);
        if (previous != null) {
            pendingByCommandId.remove(previous.commandId());
        }
        pendingByCommandId.put(command.commandId(), command);
        queuedCount.incrementAndGet();
        telemetry.event("browser_dom", "command_queued", "fill");
        logService.append(OperatorLogLevel.INFO, SOURCE, "browser DOM fill command queued", Map.of(
                SuiteTelemetry.CORRELATION_ID, correlationId(snapshot),
                "commandId", command.commandId(),
                "pageId", pageId,
                "snapshotId", snapshot.snapshotId(),
                "fields", fillFields.size(),
                "expiresAt", command.expiresAt().toString()
        ));
        return command;
    }

    public BrowserDomFillCommand enqueueChatGptPlusRelay(
            DesktopSnapshot snapshot,
            String relayId,
            String prompt
    ) {
        cleanupExpired();
        if (snapshot == null || snapshot.context() == null) {
            throw new IllegalArgumentException("Browser DOM snapshot is missing.");
        }
        String pageId = text(snapshot.context().metadata().get("pageId"));
        if (pageId.isBlank()) {
            pageId = text(snapshot.metadata().get("pageId"));
        }
        if (pageId.isBlank()) {
            throw new IllegalArgumentException("Browser DOM snapshot does not contain pageId.");
        }
        String pageUrl = sanitizePageUrl(snapshot.context().url());
        if (pageUrl.isBlank()) {
            throw new IllegalArgumentException("Browser DOM snapshot does not contain a valid page URL.");
        }
        String normalizedRelayId = normalize(relayId);
        String normalizedPrompt = normalize(prompt);
        if (normalizedRelayId.isBlank() || normalizedPrompt.isBlank()) {
            throw new IllegalArgumentException("ChatGPT Plus relayId and prompt are required.");
        }

        Instant now = Instant.now();
        BrowserDomFillCommand command = new BrowserDomFillCommand(
                UUID.randomUUID().toString(),
                pageId,
                pageUrl,
                snapshot.snapshotId(),
                now,
                now.plus(properties.getCommandTtl()),
                true,
                false,
                List.of(),
                Map.of(
                        "source", SOURCE,
                        SuiteTelemetry.CORRELATION_ID, correlationId(snapshot),
                        "commandType", "chatgpt-plus-relay",
                        "relayId", normalizedRelayId,
                        "prompt", normalizedPrompt,
                        "explicitUserGesture", true,
                        "submitDisabled", true
                )
        );

        BrowserDomFillCommand previous = pendingByPageId.put(pageId, command);
        if (previous != null) {
            pendingByCommandId.remove(previous.commandId());
        }
        pendingByCommandId.put(command.commandId(), command);
        queuedCount.incrementAndGet();
        telemetry.event("browser_dom", "command_queued", "chatgpt_plus");
        logService.append(OperatorLogLevel.INFO, SOURCE, "ChatGPT Plus relay command queued", Map.of(
                SuiteTelemetry.CORRELATION_ID, correlationId(snapshot),
                "commandId", command.commandId(),
                "pageId", pageId,
                "snapshotId", snapshot.snapshotId(),
                "relayId", normalizedRelayId,
                "expiresAt", command.expiresAt().toString()
        ));
        return command;
    }

    public Optional<BrowserDomFillCommand> next(String pageId, String pageUrl) {
        cleanupExpired();
        String normalizedPageId = normalize(pageId);
        if (normalizedPageId.isBlank()) {
            return Optional.empty();
        }
        BrowserDomFillCommand command = pendingByPageId.get(normalizedPageId);
        if (command == null || command.expired()) {
            remove(command);
            return Optional.empty();
        }
        if (!samePage(command.pageUrl(), pageUrl)) {
            rejectedCount.incrementAndGet();
            return Optional.empty();
        }
        telemetry.event("browser_dom", "command_polled", "success");
        return Optional.of(command);
    }

    public BrowserDomCommandAckResult acknowledge(String commandId, BrowserDomCommandAckRequest request) {
        cleanupExpired();
        String normalizedCommandId = normalize(commandId);
        BrowserDomFillCommand command = pendingByCommandId.get(normalizedCommandId);
        if (command == null) {
            rejectedCount.incrementAndGet();
            return new BrowserDomCommandAckResult(
                    false,
                    "browser_dom_command_not_found",
                    "Browser DOM command was not found or has expired.",
                    normalizedCommandId,
                    request == null ? 0 : request.filledCount(),
                    request == null ? 0 : request.skippedCount(),
                    request == null ? 0 : request.failedCount(),
                    Map.of()
            );
        }
        BrowserDomCommandAckRequest safeRequest = request == null
                ? new BrowserDomCommandAckRequest("", "", false, 0, 0, 1, List.of("Acknowledgement payload is missing."), Map.of())
                : request;
        if (!command.pageId().equals(safeRequest.pageId()) || !samePage(command.pageUrl(), safeRequest.pageUrl())) {
            rejectedCount.incrementAndGet();
            return new BrowserDomCommandAckResult(
                    false,
                    "browser_dom_command_page_mismatch",
                    "Browser DOM command acknowledgement does not match the originating page.",
                    command.commandId(),
                    safeRequest.filledCount(),
                    safeRequest.skippedCount(),
                    safeRequest.failedCount(),
                    Map.of("expectedPageId", command.pageId())
            );
        }

        remove(command);
        acknowledgedCount.incrementAndGet();
        telemetry.event("browser_dom", "command_acknowledged", safeRequest.ok() ? "success" : "partial");
        String code = safeRequest.ok() ? "ok" : "browser_dom_fill_partial_or_failed";
        String message = safeRequest.ok()
                ? "Browser form fields were inserted after explicit operator confirmation."
                : "Browser form insertion completed with skipped or failed fields.";
        logService.append(safeRequest.ok() ? OperatorLogLevel.INFO : OperatorLogLevel.WARN, SOURCE, "browser DOM fill command acknowledged", Map.of(
                SuiteTelemetry.CORRELATION_ID, text(command.metadata().get(SuiteTelemetry.CORRELATION_ID)),
                "commandId", command.commandId(),
                "pageId", command.pageId(),
                "filled", safeRequest.filledCount(),
                "skipped", safeRequest.skippedCount(),
                "failed", safeRequest.failedCount(),
                "ok", safeRequest.ok(),
                "warnings", safeRequest.warnings()
        ));
        return new BrowserDomCommandAckResult(
                safeRequest.ok(),
                code,
                message,
                command.commandId(),
                safeRequest.filledCount(),
                safeRequest.skippedCount(),
                safeRequest.failedCount(),
                Map.of(
                        "queuedCount", queuedCount.get(),
                        "acknowledgedCount", acknowledgedCount.get(),
                        "rejectedCount", rejectedCount.get()
                )
        );
    }

    public Map<String, Object> status() {
        cleanupExpired();
        return Map.of(
                "pendingCommands", pendingByCommandId.size(),
                "queuedCount", queuedCount.get(),
                "acknowledgedCount", acknowledgedCount.get(),
                "rejectedCount", rejectedCount.get(),
                "commandTtl", properties.getCommandTtl().toString(),
                "submitEnabled", false,
                "preserveExistingValues", properties.isPreserveExistingValues(),
                "writeEnabled", properties.isWriteEnabled()
        );
    }

    private void cleanupExpired() {
        for (BrowserDomFillCommand command : List.copyOf(pendingByCommandId.values())) {
            if (command.expired()) {
                telemetry.event("browser_dom", "command_expired", "expired");
                remove(command);
            }
        }
    }

    private void remove(BrowserDomFillCommand command) {
        if (command == null) {
            return;
        }
        pendingByCommandId.remove(command.commandId(), command);
        pendingByPageId.remove(command.pageId(), command);
    }

    private boolean samePage(String expected, String actual) {
        String left = sanitizePageUrl(expected);
        String right = sanitizePageUrl(actual);
        return !left.isBlank() && left.equals(right);
    }

    private String sanitizePageUrl(String raw) {
        String value = normalize(raw);
        if (value.isBlank()) {
            return "";
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
                return "";
            }
            return new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath(),
                    null,
                    null
            ).toString();
        } catch (URISyntaxException ignored) {
            return "";
        }
    }

    private String correlationId(DesktopSnapshot snapshot) {
        if (snapshot == null) {
            return "";
        }
        return firstText(
                text(snapshot.metadata().get(SuiteTelemetry.CORRELATION_ID)),
                snapshot.context() == null
                        ? ""
                        : text(snapshot.context().metadata().get(SuiteTelemetry.CORRELATION_ID))
        );
    }

    private String normalizeAction(String action) {
        return switch (normalize(action).toLowerCase()) {
            case "select" -> "select";
            case "check" -> "check";
            case "uncheck" -> "uncheck";
            default -> "fill";
        };
    }

    private boolean metadataBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            return switch (text.trim().toLowerCase()) {
                case "true", "1", "yes", "on" -> true;
                case "false", "0", "no", "off" -> false;
                default -> fallback;
            };
        }
        return fallback;
    }

    private String firstText(String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String text(Object value) {
        return value == null ? "" : normalize(String.valueOf(value));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= 2_048 ? normalized : normalized.substring(0, 2_048);
    }
}
