package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopAgentModels.DesktopActiveFormInfo;
import com.takesome.springsuite.desktop.DesktopAgentModels.DesktopAgentStatus;
import com.takesome.springsuite.desktop.DesktopAgentModels.DesktopFormSuggestion;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionDryRunRequest;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopActionExecutionRequest;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovalRequest;
import com.takesome.springsuite.desktop.DesktopApprovalModels.DesktopApprovedAction;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshotResult;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFieldPlan;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFocusContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormField;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormFillPlan;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormFillRequest;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopHintRequest;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopHintResponse;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class DesktopAgentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DesktopAgentService.class);
    private static final String NATIVE_BRIDGE_ID = "windows-ui-automation-bridge-adapter";

    private final DesktopHelperProperties helperProperties;
    private final DesktopAgentProperties properties;
    private final BrowserDomProperties browserDomProperties;
    private final DesktopAgentSidecarProperties sidecarProperties;
    private final DesktopAgentSidecarRuntime sidecarRuntime;
    private final DesktopBridgeService bridgeService;
    private final DesktopHelperService helperService;
    private final DesktopApprovalService approvalService;
    private final DesktopAgentUi ui;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "spring-suite-desktop-agent");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean scanInFlight = new AtomicBoolean();
    private final AtomicLong scanCount = new AtomicLong();
    private final AtomicLong formDetectionCount = new AtomicLong();
    private final AtomicLong actionExecutionCount = new AtomicLong();
    private final Map<String, Instant> suppressedUntil = new ConcurrentHashMap<>();

    private volatile Instant startedAt;
    private volatile Instant lastScanAt;
    private volatile Instant lastFormDetectedAt;
    private volatile String candidateSignature = "";
    private volatile Instant candidateSince;
    private volatile String activeSignature = "";
    private volatile String lastCode = "idle";
    private volatile String lastMessage = "Агент рабочего стола ещё не запущен.";
    private volatile DesktopSnapshot currentSnapshot;
    private volatile DesktopSnapshot externalSnapshot;
    private volatile DesktopFormSuggestion currentSuggestion;

    public DesktopAgentService(
            DesktopHelperProperties helperProperties,
            DesktopAgentProperties properties,
            BrowserDomProperties browserDomProperties,
            DesktopAgentSidecarProperties sidecarProperties,
            DesktopAgentSidecarRuntime sidecarRuntime,
            DesktopBridgeService bridgeService,
            DesktopHelperService helperService,
            DesktopApprovalService approvalService,
            DesktopAgentUi ui
    ) {
        this.helperProperties = helperProperties;
        this.properties = properties;
        this.browserDomProperties = browserDomProperties;
        this.sidecarProperties = sidecarProperties;
        this.sidecarRuntime = sidecarRuntime;
        this.bridgeService = bridgeService;
        this.helperService = helperService;
        this.approvalService = approvalService;
        this.ui = ui;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.isEnabled() || !helperProperties.isEnabled()) {
            updateState("disabled", "Агент рабочего стола отключён в конфигурации.");
            return;
        }
        if (!isWindows() && !browserDomProperties.isEnabled()) {
            updateState("unsupported_platform", "Нативное обнаружение форм требует Windows, а browser DOM recognition отключён.");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        startedAt = Instant.now();
        // The tray is initialized before native sidecar startup so SpringSuite always lands in the tray,
        // even when the external agent is missing or unhealthy.
        ui.initialize(this::scanNow, this::pause, this::resume, this::restartSidecar);
        if (isWindows() && sidecarProperties.isAutoStart()) {
            DesktopAgentSidecarRuntime.SidecarStatus sidecar = sidecarRuntime.start();
            if (!sidecar.healthy()) {
                ui.notifyWarning("SpringSuite · Агент рабочего стола", "Нативный агент не запущен: " + sidecar.message());
            }
        }
        long intervalMs = Math.max(250L, properties.getPollInterval().toMillis());
        scheduler.scheduleWithFixedDelay(this::pollSafely, 1500L, intervalMs, TimeUnit.MILLISECONDS);
        updateState(
                "running",
                isWindows()
                        ? "SpringSuite отслеживает нативные и веб-формы."
                        : "SpringSuite ожидает снимки веб-форм от Browser Form Bridge."
        );
    }

    public void scanNow() {
        if (!running.get()) {
            start();
        }
        scheduler.execute(this::pollSafely);
    }

    public void acceptExternalSnapshot(DesktopSnapshot snapshot) {
        if (snapshot == null || snapshot.context() == null || !isSnapshotFresh(snapshot)) {
            return;
        }
        externalSnapshot = snapshot;
        if (!running.get()) {
            start();
        }
        if (running.get()) {
            scheduler.execute(this::pollSafely);
        }
    }

    public void pause() {
        paused.set(true);
        activeSignature = "";
        currentSuggestion = null;
        ui.hideSuggestion();
        updateState("paused", "Подсказки для активных форм приостановлены.");
        ui.notifyInfo("SpringSuite · Агент рабочего стола", "Подсказки для форм приостановлены.");
    }

    public void resume() {
        paused.set(false);
        candidateSignature = "";
        candidateSince = null;
        updateState("running", "Подсказки для активных форм возобновлены.");
        ui.notifyInfo("SpringSuite · Агент рабочего стола", "Подсказки для форм возобновлены.");
        scanNow();
    }

    public void restartSidecar() {
        if (!isWindows()) {
            updateState("unsupported_platform", "Нативный desktop-agent доступен только в Windows; browser DOM recognition продолжает работать.");
            return;
        }
        scheduler.execute(() -> {
            DesktopAgentSidecarRuntime.SidecarStatus status = sidecarRuntime.restart();
            if (status.healthy()) {
                updateState("sidecar_restarted", "Нативный desktop-agent успешно перезапущен.");
                ui.notifyInfo("SpringSuite · Агент рабочего стола", "Нативный desktop-agent перезапущен.");
                scanNow();
            } else {
                updateState(status.code(), status.message());
                ui.notifyWarning("SpringSuite · Агент рабочего стола", "Не удалось запустить desktop-agent: " + status.message());
            }
        });
    }

    public DesktopAgentStatus status() {
        return new DesktopAgentStatus(
                properties.isEnabled() && helperProperties.isEnabled(),
                running.get(),
                paused.get(),
                ui.trayAvailable(),
                ui.available() && properties.isOverlayEnabled(),
                startedAt,
                lastScanAt,
                lastFormDetectedAt,
                activeSignature,
                lastCode,
                lastMessage,
                scanCount.get(),
                formDetectionCount.get(),
                actionExecutionCount.get(),
                Map.of(
                        "pollInterval", properties.getPollInterval().toString(),
                        "stableFor", properties.getStableFor().toString(),
                        "repeatAfter", properties.getRepeatAfter().toString(),
                        "profileKeys", properties.getAutofillProfile().keySet().stream().sorted().toList(),
                        "captureToolId", helperProperties.getCaptureToolId(),
                        "browserDomEnabled", browserDomProperties.isEnabled(),
                        "externalSnapshotFresh", freshExternalSnapshot() != null,
                        "sidecar", sidecarRuntime.status()
                )
        );
    }

    public DesktopActiveFormInfo currentForm() {
        DesktopFormSuggestion suggestion = currentSuggestion;
        DesktopSnapshot snapshot = currentSnapshot;
        return new DesktopActiveFormInfo(
                suggestion != null && snapshot != null && isSnapshotFresh(snapshot),
                lastScanAt,
                snapshot,
                suggestion,
                Map.of(
                        "activeSignature", activeSignature,
                        "candidateSignature", candidateSignature,
                        "snapshotSource", snapshot == null ? "" : snapshot.source(),
                        "sidecar", sidecarRuntime.status()
                )
        );
    }

    private void pollSafely() {
        if (!running.get() || paused.get() || !scanInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            poll();
        } catch (Exception ex) {
            updateState("scan_failed", safeMessage(ex));
            LOGGER.debug("Ошибка сканирования активной формы", ex);
        } finally {
            scanInFlight.set(false);
        }
    }

    private void poll() throws Exception {
        lastScanAt = Instant.now();
        scanCount.incrementAndGet();

        DesktopSnapshot browserSnapshot = freshExternalSnapshot();
        if (browserSnapshot != null) {
            processSnapshot(browserSnapshot);
            return;
        }
        if (!isWindows()) {
            clearCandidate();
            updateState("waiting_browser_dom", "Ожидается свежий снимок веб-формы от Browser Form Bridge.");
            return;
        }

        Map<String, Object> raw = sidecarRuntime.inspect();
        DesktopSnapshotResult snapshotResult = bridgeService.ingest(raw);
        if (!snapshotResult.ok() || snapshotResult.snapshot() == null) {
            updateState(snapshotResult.code(), snapshotResult.message());
            return;
        }
        processSnapshot(snapshotResult.snapshot());
    }

    private void processSnapshot(DesktopSnapshot snapshot) {
        currentSnapshot = snapshot;
        DesktopFocusContext context = snapshot.context();
        List<DesktopFormField> fields = context.form().fields();
        if (fields.size() < properties.getMinimumFieldCount()) {
            clearCandidate();
            updateState("no_active_form", "Активная форма с доступными для заполнения полями не обнаружена.");
            return;
        }

        String signature = formSignature(context);
        Instant now = Instant.now();
        if (!signature.equals(candidateSignature)) {
            candidateSignature = signature;
            candidateSince = now;
            updateState("form_stabilizing", "Форма обнаружена; ожидается стабилизация интерфейса перед показом действий.");
            return;
        }
        if (candidateSince == null || now.isBefore(candidateSince.plus(properties.getStableFor()))) {
            return;
        }
        Instant suppressed = suppressedUntil.get(signature);
        if (suppressed != null && now.isBefore(suppressed)) {
            updateState("form_suppressed", "Подсказки для текущей формы временно скрыты.");
            return;
        }
        if (signature.equals(activeSignature)) {
            return;
        }

        DesktopFormSuggestion suggestion = buildSuggestion(signature, snapshot);
        currentSuggestion = suggestion;
        activeSignature = signature;
        lastFormDetectedAt = now;
        formDetectionCount.incrementAndGet();
        updateState("form_detected", suggestion.summary());
        ui.showSuggestion(
                suggestion,
                () -> scheduler.execute(() -> executeSuggestion(suggestion)),
                () -> scheduler.execute(() -> showHints(suggestion)),
                () -> suppress(suggestion.signature())
        );
    }

    private DesktopFormSuggestion buildSuggestion(String signature, DesktopSnapshot snapshot) {
        DesktopFocusContext context = snapshot.context();
        DesktopFormFillPlan plan = helperService.planFormFill(new DesktopFormFillRequest(
                context,
                "Помоги безопасно заполнить активную форму на рабочем столе",
                properties.getLocale(),
                properties.getAutofillProfile(),
                properties.getConstraints(),
                false
        ));
        DesktopHintResponse hints = helperService.hints(new DesktopHintRequest(
                context,
                "Предложи безопасные действия для активной формы на рабочем столе",
                properties.getLocale(),
                Map.of("surface", "desktop-agent-overlay")
        ));
        List<DesktopApprovedAction> actions = approvedActions(plan, snapshot);
        int[] point = overlayPoint(context);
        String application = firstText(context.activeApplication(), "активное приложение");
        boolean browserDom = isBrowserDomSnapshot(snapshot);
        String summary = browserDom
                ? "Распознана веб-форма: " + context.form().fields().size() + " полей. Доступны анализ и план заполнения; DOM-запись отключена."
                : actions.isEmpty()
                        ? "Обнаружена форма: " + context.form().fields().size() + " полей. Профиль не содержит безопасных значений для автоматического заполнения."
                        : "Обнаружена форма: " + context.form().fields().size() + " полей. Можно безопасно заполнить " + actions.size() + ".";
        return new DesktopFormSuggestion(
                signature,
                snapshot,
                plan,
                hints,
                actions,
                point[0],
                point[1],
                "SpringSuite В· " + application,
                summary,
                Map.of(
                        "windowTitle", context.activeWindowTitle(),
                        "url", context.url(),
                        "snapshotSource", snapshot.source(),
                        "browserDom", browserDom
                )
        );
    }

    private List<DesktopApprovedAction> approvedActions(DesktopFormFillPlan plan, DesktopSnapshot snapshot) {
        if (plan == null || !plan.ok() || isBrowserDomSnapshot(snapshot)) {
            return List.of();
        }
        ArrayList<DesktopApprovedAction> actions = new ArrayList<>();
        for (DesktopFieldPlan field : plan.fields()) {
            if (actions.size() >= properties.getMaximumActionCount()) {
                break;
            }
            if (field.sensitive() || field.needsUserReview()) {
                continue;
            }
            if (!isNativeWriteAction(field.action())) {
                continue;
            }
            if (("fill".equals(field.action()) || "type".equals(field.action()) || "paste".equals(field.action()) || "select".equals(field.action()))
                    && field.value().isBlank()) {
                continue;
            }
            actions.add(new DesktopApprovedAction(
                    "desktop-agent:" + field.fieldId(),
                    field.action(),
                    field.fieldId(),
                    field.label(),
                    field.value(),
                    true,
                    false,
                    false,
                    field.reason(),
                    Map.of(
                            "bridgeId", NATIVE_BRIDGE_ID,
                            "source", "desktop-agent-overlay",
                            "confidence", field.confidence()
                    )
            ));
        }
        return List.copyOf(actions);
    }

    private void executeSuggestion(DesktopFormSuggestion suggestion) {
        if (suggestion.actions().isEmpty()) {
            ui.showTransientMessage("Нет безопасных значений для автоматического заполнения. Добавьте autofill-profile в конфигурацию.");
            return;
        }
        try {
            DesktopApprovalModels.DesktopApprovalResult approval = approvalService.createApproval(new DesktopApprovalRequest(
                    suggestion.snapshot().snapshotId(),
                    "desktop-agent-overlay-click",
                    System.getProperty("user.name", "desktop-user"),
                    suggestion.plan(),
                    suggestion.actions(),
                    List.of("desktop.actions.dry-run", "desktop.actions.execute"),
                    false,
                    false,
                    helperProperties.getApprovalTokenTtlSeconds(),
                    Map.of(
                            "explicitUserGesture", true,
                            "surface", "desktop-agent-overlay",
                            "formSignature", suggestion.signature()
                    )
            ));
            if (!approval.ok() || approval.token() == null) {
                failAction(approval.code(), approval.message());
                return;
            }

            DesktopApprovalModels.DesktopActionDryRunResult dryRun = approvalService.dryRun(new DesktopActionDryRunRequest(
                    approval.token().tokenId(),
                    suggestion.snapshot().snapshotId(),
                    suggestion.actions(),
                    false,
                    Map.of("surface", "desktop-agent-overlay")
            ));
            if (!dryRun.ok() || !dryRun.wouldExecute()) {
                failAction(dryRun.code(), dryRun.message());
                return;
            }

            DesktopApprovalModels.DesktopActionExecutionResult execution = approvalService.execute(new DesktopActionExecutionRequest(
                    approval.token().tokenId(),
                    suggestion.snapshot().snapshotId(),
                    suggestion.actions(),
                    true,
                    true,
                    Map.of("surface", "desktop-agent-overlay", "explicitUserGesture", true)
            ));
            if (!execution.ok() || !execution.executed()) {
                failAction(execution.code(), execution.message());
                return;
            }
            actionExecutionCount.incrementAndGet();
            suppress(suggestion.signature());
            updateState("action_executed", "Заполнено " + suggestion.actions().size() + " полей в активной форме.");
            ui.notifyInfo("SpringSuite", "Заполнено полей: " + suggestion.actions().size());
            ui.hideSuggestion();
        } catch (Exception ex) {
            failAction("action_failed", safeMessage(ex));
        }
    }

    private void showHints(DesktopFormSuggestion suggestion) {
        DesktopHintResponse hints = suggestion.hints();
        if (hints == null || hints.hints().isEmpty()) {
            ui.setOverlayMessage("Дополнительных подсказок для этой формы нет.");
            return;
        }
        String message = hints.hints().stream()
                .limit(3)
                .map(hint -> hint.title() + ": " + hint.message())
                .reduce((left, right) -> left + " В· " + right)
                .orElse(hints.summary());
        ui.setOverlayMessage(message);
        ui.notifyInfo("SpringSuite · подсказки формы", truncate(message, 240));
    }

    private void suppress(String signature) {
        if (signature == null || signature.isBlank()) {
            return;
        }
        suppressedUntil.put(signature, Instant.now().plus(properties.getRepeatAfter()));
        if (signature.equals(activeSignature)) {
            activeSignature = "";
        }
    }

    private void clearCandidate() {
        candidateSignature = "";
        candidateSince = null;
        activeSignature = "";
        currentSuggestion = null;
        ui.hideSuggestion();
    }

    private String formSignature(DesktopFocusContext context) {
        StringBuilder material = new StringBuilder()
                .append(context.activeApplication()).append('|')
                .append(context.activeWindowTitle()).append('|')
                .append(context.url()).append('|')
                .append(context.form().id()).append('|')
                .append(context.form().action()).append('|')
                .append(context.form().method()).append('|');
        for (DesktopFormField field : context.form().fields()) {
            material.append(field.id()).append(':')
                    .append(field.type()).append(':')
                    .append(field.required()).append(':')
                    .append(fieldHasValue(field)).append('|');
        }
        return Integer.toUnsignedString(material.toString().hashCode(), 16);
    }

    private int[] overlayPoint(DesktopFocusContext context) {
        DesktopFormField anchor = context.form().fields().stream()
                .filter(DesktopFormField::focused)
                .findFirst()
                .orElse(context.form().fields().get(0));
        Map<String, Object> bounds = mapValue(anchor.metadata().get("bounds"));
        int right = intValue(bounds.get("right"));
        int top = intValue(bounds.get("top"));
        if (right != 0 || top != 0) {
            return new int[]{right + 10, top};
        }
        Map<String, Object> activeWindow = mapValue(context.metadata().get("activeWindow"));
        Map<String, Object> windowBounds = mapValue(activeWindow.get("bounds"));
        int windowRight = intValue(windowBounds.get("right"));
        int windowTop = intValue(windowBounds.get("top"));
        return new int[]{Math.max(20, windowRight - 430), Math.max(20, windowTop + 48)};
    }

    private void failAction(String code, String message) {
        updateState(code, message);
        ui.notifyWarning("SpringSuite · действие заблокировано", truncate(message, 240));
        ui.showTransientMessage("Действие не выполнено: " + message);
    }

    private void updateState(String code, String message) {
        lastCode = code == null ? "" : code;
        lastMessage = message == null ? "" : message;
    }

    private boolean isNativeWriteAction(String action) {
        return switch (action == null ? "" : action) {
            case "fill", "type", "paste", "select", "check", "uncheck" -> true;
            default -> false;
        };
    }

    private boolean fieldHasValue(DesktopFormField field) {
        if (field == null || !field.value().isBlank()) {
            return field != null;
        }
        Object value = field.metadata().get("valuePresent");
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private DesktopSnapshot freshExternalSnapshot() {
        DesktopSnapshot snapshot = externalSnapshot;
        if (snapshot == null) {
            return null;
        }
        if (!isSnapshotFresh(snapshot)) {
            if (externalSnapshot == snapshot) {
                externalSnapshot = null;
            }
            return null;
        }
        return snapshot;
    }

    private boolean isSnapshotFresh(DesktopSnapshot snapshot) {
        return snapshot != null
                && !snapshot.stale()
                && snapshot.expiresAt() != null
                && !Instant.now().isAfter(snapshot.expiresAt());
    }

    private boolean isBrowserDomSnapshot(DesktopSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        if (BrowserDomService.SOURCE.equals(snapshot.source())) {
            return true;
        }
        Object browserDom = snapshot.context().form().metadata().get("browserDom");
        return browserDom instanceof Boolean value && value;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value == null ? "0" : String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
        ui.hideSuggestion();
    }
}
