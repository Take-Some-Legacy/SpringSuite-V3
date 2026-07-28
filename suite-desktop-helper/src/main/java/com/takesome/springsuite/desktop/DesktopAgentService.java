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
import com.takesome.springsuite.observability.SuiteTelemetry;
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
public class DesktopAgentService implements DesktopSnapshotConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DesktopAgentService.class);
    private static final String NATIVE_BRIDGE_ID = "windows-ui-automation-bridge-adapter";
    private static final String FILL_SOURCE_MEMORY = "memory";
    private static final String FILL_SOURCE_AI = "ai";
    private static final String FILL_SOURCE_CHATGPT = ChatGptFormRelayService.SOURCE_ID;

    private final DesktopHelperProperties helperProperties;
    private final DesktopAgentProperties properties;
    private final BrowserDomProperties browserDomProperties;
    private final BrowserDomCommandService browserDomCommandService;
    private final DesktopAgentSidecarProperties sidecarProperties;
    private final DesktopAgentSidecarRuntime sidecarRuntime;
    private final DesktopBridgeService bridgeService;
    private final DesktopHelperService helperService;
    private final ChatGptFormRelayService chatGptRelayService;
    private final DesktopApprovalService approvalService;
    private final DesktopAgentUi ui;
    private final SuiteTelemetry telemetry;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "spring-suite-desktop-agent");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean scanInFlight = new AtomicBoolean();
    private final AtomicBoolean aiFillInFlight = new AtomicBoolean();
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
            BrowserDomCommandService browserDomCommandService,
            DesktopAgentSidecarProperties sidecarProperties,
            DesktopAgentSidecarRuntime sidecarRuntime,
            DesktopBridgeService bridgeService,
            DesktopHelperService helperService,
            ChatGptFormRelayService chatGptRelayService,
            DesktopApprovalService approvalService,
            DesktopAgentUi ui,
            SuiteTelemetry telemetry
    ) {
        this.helperProperties = helperProperties;
        this.properties = properties;
        this.browserDomProperties = browserDomProperties;
        this.browserDomCommandService = browserDomCommandService;
        this.sidecarProperties = sidecarProperties;
        this.sidecarRuntime = sidecarRuntime;
        this.bridgeService = bridgeService;
        this.helperService = helperService;
        this.chatGptRelayService = chatGptRelayService;
        this.approvalService = approvalService;
        this.ui = ui;
        this.telemetry = telemetry;
        telemetry.registerGauge("desktop_agent", "scan_inflight", () -> scanInFlight.get() ? 1 : 0);
        telemetry.registerGauge("desktop_agent", "ai_fill_inflight", () -> aiFillInFlight.get() ? 1 : 0);
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

    @Override
    public void acceptSnapshot(DesktopSnapshot snapshot) {
        acceptExternalSnapshot(snapshot);
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
        SuiteTelemetry.Operation operation = telemetry.start("desktop_agent", "scan");
        try {
            poll();
            operation.success();
        } catch (Exception ex) {
            operation.failure("scan_failed");
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
        DesktopFocusContext context = snapshot.context();
        if (aiFillInFlight.get() && currentSuggestion != null
                && !formSignature(context).equals(currentSuggestion.signature())) {
            return;
        }
        currentSnapshot = snapshot;
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
            if (aiFillInFlight.get()) {
                return;
            }
            DesktopFormSuggestion existing = currentSuggestion;
            if (existing != null && FILL_SOURCE_CHATGPT.equals(normalizeFillSource(
                    textValue(existing.metadata().get("fillSource"))))) {
                long relayRevision = chatGptRelayService.revisionFor(signature);
                long suggestionRevision = longValue(existing.metadata().get("relayRevision"));
                if (relayRevision > suggestionRevision && chatGptRelayService.readyFor(signature).isPresent()) {
                    DesktopFormSuggestion refreshed = buildSuggestion(signature, snapshot, FILL_SOURCE_CHATGPT);
                    currentSuggestion = refreshed;
                    updateState("chatgpt_relay_ready", refreshed.summary());
                    showSuggestionOverlay(refreshed);
                    return;
                }
            }
            if (existing != null && (existing.snapshot() == null
                    || !snapshot.snapshotId().equals(existing.snapshot().snapshotId()))) {
                DesktopFormSuggestion refreshed = refreshSuggestionSnapshot(existing, snapshot);
                currentSuggestion = refreshed;
                updateState("form_context_updated", refreshed.summary());
                showSuggestionOverlay(refreshed);
            }
            return;
        }

        DesktopFormSuggestion suggestion = buildSuggestion(signature, snapshot, FILL_SOURCE_MEMORY);
        currentSuggestion = suggestion;
        activeSignature = signature;
        lastFormDetectedAt = now;
        formDetectionCount.incrementAndGet();
        telemetry.event("desktop_agent", "form_detected", "success");
        updateState("form_detected", suggestion.summary());
        showSuggestionOverlay(suggestion);
    }

    private void showSuggestionOverlay(DesktopFormSuggestion suggestion) {
        ui.showSuggestion(
                suggestion,
                () -> scheduler.execute(() -> executeSuggestion(suggestion)),
                () -> scheduler.execute(() -> showHints(suggestion)),
                source -> scheduler.execute(() -> switchFillSource(suggestion, source)),
                () -> suppress(suggestion.signature())
        );
    }

    private void switchFillSource(DesktopFormSuggestion suggestion, String requestedSource) {
        String source = normalizeFillSource(requestedSource);
        String currentSource = textValue(suggestion.metadata().get("fillSource"));
        if (source.equals(currentSource)) {
            return;
        }
        if (suggestion.snapshot() == null || !isSnapshotFresh(suggestion.snapshot())) {
            ui.showTransientMessage("Форма изменилась или устарела. Выполните повторное сканирование.");
            return;
        }

        if (FILL_SOURCE_CHATGPT.equals(currentSource) && !FILL_SOURCE_CHATGPT.equals(source)) {
            chatGptRelayService.cancel(suggestion.signature(), "Operator selected another fill source.");
        }
        ui.setOverlayMessage(FILL_SOURCE_CHATGPT.equals(source)
                ? "ChatGPT Plus готов. Нажмите «Заполнить», чтобы отправить запрос в текущий чат."
                : "Читаю значения из локальной памяти автозаполнения…");
        DesktopFormSuggestion refreshed = buildSuggestion(suggestion.signature(), suggestion.snapshot(), source);
        currentSuggestion = refreshed;
        updateState("fill_source_changed", "Источник заполнения: " + source);
        showSuggestionOverlay(refreshed);
    }

    private String normalizeFillSource(String source) {
        String normalized = source == null ? "" : source.trim();
        if (FILL_SOURCE_AI.equalsIgnoreCase(normalized)) {
            return FILL_SOURCE_CHATGPT;
        }
        if (FILL_SOURCE_CHATGPT.equalsIgnoreCase(normalized) || "chatgpt".equalsIgnoreCase(normalized)) {
            return FILL_SOURCE_CHATGPT;
        }
        return FILL_SOURCE_MEMORY;
    }

    private DesktopFormSuggestion refreshSuggestionSnapshot(
            DesktopFormSuggestion existing,
            DesktopSnapshot snapshot
    ) {
        DesktopFocusContext context = snapshot.context();
        DesktopFormField activeField = context.form().fields().stream()
                .filter(DesktopFormField::focused)
                .findFirst()
                .orElse(context.form().fields().get(0));
        String activeFieldPrompt = textValue(activeField.metadata().get("contextPrompt"));
        String activeFieldLabel = activeField.label();
        String activeFieldName = firstText(
                activeFieldPrompt.equalsIgnoreCase(activeFieldLabel) ? "" : activeFieldLabel,
                activeField.name(),
                activeField.id(),
                activeField.type(),
                "неизвестное поле"
        );

        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(existing.metadata());
        metadata.put("windowTitle", context.activeWindowTitle());
        metadata.put("url", context.url());
        metadata.put("snapshotSource", snapshot.source());
        metadata.put("browserDom", isBrowserDomSnapshot(snapshot));
        metadata.put("activeFieldName", activeFieldName);
        metadata.put("activeFieldPlaceholder", activeField.placeholder());
        metadata.put("activeFieldPrompt", activeFieldPrompt);
        metadata.put("activeFieldType", activeField.type());

        List<DesktopApprovedAction> actions = approvedActions(existing.plan(), snapshot);
        String source = normalizeFillSource(textValue(metadata.get("fillSource")));
        String summary = existing.summary();
        if (actions.isEmpty() && !existing.actions().isEmpty()) {
            summary = FILL_SOURCE_AI.equals(source)
                    ? "AI-черновик больше не применим к текущему состоянию формы. Нажмите «Заполнить», чтобы пересоздать его."
                    : "Ранее предложенные значения больше не применимы к текущему состоянию формы.";
        }

        return new DesktopFormSuggestion(
                existing.signature(),
                snapshot,
                existing.plan(),
                existing.hints(),
                actions,
                existing.x(),
                existing.y(),
                existing.title(),
                summary,
                metadata
        );
    }

    private DesktopFormSuggestion buildSuggestion(String signature, DesktopSnapshot snapshot, String requestedSource) {
        return buildSuggestion(signature, snapshot, requestedSource, false);
    }

    private DesktopFormSuggestion buildSuggestion(
            String signature,
            DesktopSnapshot snapshot,
            String requestedSource,
            boolean generateAi
    ) {
        DesktopFocusContext context = snapshot.context();
        String fillSource = normalizeFillSource(requestedSource);
        ChatGptFormRelayService.RelayResult relayResult = null;
        Map<String, Object> relayView = Map.of();
        Map<String, Object> profile = properties.getAutofillProfile();
        String goal = "Заполни форму безопасными значениями из локальной памяти";

        if (FILL_SOURCE_CHATGPT.equals(fillSource)) {
            relayView = chatGptRelayService.publish(signature, snapshot, properties.getLocale());
            relayResult = chatGptRelayService.readyFor(signature).orElse(null);
            if (relayResult != null) {
                profile = new LinkedHashMap<>(relayResult.profile());
            } else {
                profile = Map.of();
            }
            goal = "Примени только локально проверенный черновик, возвращённый ChatGPT 5.6 через NorthStar MCP";
        } else if (FILL_SOURCE_AI.equals(fillSource)) {
            profile = Map.of();
            goal = "Сформируй безопасное значение по placeholder или ближайшему prompt активного поля формы";
        }

        DesktopFormFillRequest fillRequest = new DesktopFormFillRequest(
                context,
                goal,
                properties.getLocale(),
                profile,
                properties.getConstraints(),
                false
        );
        DesktopFormFillPlan plan = FILL_SOURCE_AI.equals(fillSource)
                ? generateAi
                        ? helperService.planFormFillWithAi(fillRequest)
                        : helperService.planFormFillExternal(fillRequest)
                : FILL_SOURCE_CHATGPT.equals(fillSource)
                        ? helperService.planFormFillExternal(fillRequest)
                        : helperService.planFormFill(fillRequest);
        DesktopHintResponse hints = helperService.hints(new DesktopHintRequest(
                context,
                "Предложи безопасные действия для активной формы на рабочем столе",
                properties.getLocale(),
                Map.of(
                        "surface", "desktop-agent-overlay",
                        "skipAi", true,
                        "reason", "The explicit Fill action owns the only AI request for this interaction."
                )
        ));
        List<DesktopApprovedAction> actions = approvedActions(plan, snapshot);
        int[] point = overlayPoint(context);
        String application = firstText(context.activeApplication(), "активное приложение");
        boolean browserDom = isBrowserDomSnapshot(snapshot);
        DesktopFormField activeField = context.form().fields().stream()
                .filter(DesktopFormField::focused)
                .findFirst()
                .orElse(context.form().fields().get(0));
        String activeFieldPrompt = textValue(activeField.metadata().get("contextPrompt"));
        String activeFieldLabel = activeField.label();
        String activeFieldName = firstText(
                activeFieldPrompt.equalsIgnoreCase(activeFieldLabel) ? "" : activeFieldLabel,
                activeField.name(),
                activeField.id(),
                activeField.type(),
                "неизвестное поле"
        );
        String activeFieldPlaceholder = activeField.placeholder();

        String summary;
        if (FILL_SOURCE_CHATGPT.equals(fillSource)) {
            String relayStatus = textValue(relayView.get("status"));
            if (relayResult == null) {
                summary = "Нажмите «Заполнить»: SpringSuite отправит служебный turn в текущий ChatGPT Plus-чат и заполнит поле после MCP-проверки.";
            } else if (actions.isEmpty()) {
                summary = "ChatGPT 5.6 вернул черновик, но ни одно значение не прошло локальные safety-проверки.";
            } else {
                summary = "ChatGPT Plus подготовил безопасное значение; SpringSuite выполняет заполнение без отправки формы.";
            }
        } else if (FILL_SOURCE_AI.equals(fillSource) && !generateAi) {
            summary = "Нажмите «Заполнить»: ИИ возьмёт placeholder или ближайший prompt поля «"
                    + activeFieldName + "», создаст значение и заполнит поле без отправки формы.";
        } else if (actions.isEmpty()) {
            String aiWarning = textValue(plan.metadata().get("aiWarning"));
            summary = FILL_SOURCE_AI.equals(fillSource)
                    ? firstText(
                            aiWarning,
                            "ИИ не предложил безопасного значения для поля «" + activeFieldName
                                    + "». Личные и чувствительные данные не генерируются."
                    )
                    : "Для поля «" + activeFieldName
                            + "» нет подходящего значения в локальной памяти автозаполнения.";
        } else {
            summary = FILL_SOURCE_AI.equals(fillSource)
                    ? "ИИ подготовил значение по prompt активного поля. Выполняю безопасное заполнение."
                    : browserDom
                            ? "Найдены значения в локальной памяти. Проверьте их и нажмите «Заполнить»."
                            : "Можно безопасно заполнить " + actions.size() + ".";
        }

        LinkedHashMap<String, Object> suggestionMetadata = new LinkedHashMap<>();
        suggestionMetadata.put("windowTitle", context.activeWindowTitle());
        suggestionMetadata.put("url", context.url());
        suggestionMetadata.put("snapshotSource", snapshot.source());
        suggestionMetadata.put("browserDom", browserDom);
        suggestionMetadata.put("fillSource", fillSource);
        suggestionMetadata.put("activeFieldName", activeFieldName);
        suggestionMetadata.put("activeFieldPlaceholder", activeFieldPlaceholder);
        suggestionMetadata.put("activeFieldPrompt", activeFieldPrompt);
        suggestionMetadata.put("activeFieldType", activeField.type());
        suggestionMetadata.put("aiGenerated", generateAi);
        if (FILL_SOURCE_CHATGPT.equals(fillSource)) {
            suggestionMetadata.put("relayId", textValue(relayView.get("relayId")));
            suggestionMetadata.put("relayStatus", textValue(relayView.get("status")));
            suggestionMetadata.put("relayRevision", longValue(relayView.get("revision")));
            suggestionMetadata.put("relayValueCount", relayResult == null ? 0 : relayResult.profile().size());
        }

        return new DesktopFormSuggestion(
                signature,
                snapshot,
                plan,
                hints,
                actions,
                point[0],
                point[1],
                "SpringSuite · " + application,
                summary,
                suggestionMetadata
        );
    }

    private List<DesktopApprovedAction> approvedActions(DesktopFormFillPlan plan, DesktopSnapshot snapshot) {
        if (plan == null || !plan.ok() || snapshot == null || snapshot.context() == null) {
            return List.of();
        }
        boolean browserDom = isBrowserDomSnapshot(snapshot);
        Map<String, DesktopFormField> fieldsById = new LinkedHashMap<>();
        for (DesktopFormField formField : snapshot.context().form().fields()) {
            fieldsById.put(formField.id(), formField);
            if (!formField.name().isBlank()) {
                fieldsById.putIfAbsent(formField.name(), formField);
            }
        }

        ArrayList<DesktopApprovedAction> actions = new ArrayList<>();
        for (DesktopFieldPlan field : plan.fields()) {
            if (properties.getMaximumActionCount() > 0 && actions.size() >= properties.getMaximumActionCount()) {
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

            DesktopFormField formField = fieldsById.get(field.fieldId());
            if (formField != null && fieldHasValue(formField)) {
                continue;
            }
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("bridgeId", browserDom ? "browser-dom-bridge-adapter" : NATIVE_BRIDGE_ID);
            metadata.put("source", "desktop-agent-overlay");
            metadata.put("confidence", field.confidence());
            metadata.put("browserDom", browserDom);
            metadata.put("pageId", textValue(snapshot.context().metadata().get("pageId")));
            if (formField != null) {
                metadata.put("cssSelector", textValue(formField.metadata().get("cssSelector")));
                metadata.put("fieldType", formField.type());
                metadata.put("valuePresent", fieldHasValue(formField));
            }
            actions.add(new DesktopApprovedAction(
                    "desktop-agent:" + field.fieldId(),
                    browserDom && ("type".equals(field.action()) || "paste".equals(field.action())) ? "fill" : field.action(),
                    field.fieldId(),
                    field.label(),
                    field.value(),
                    true,
                    false,
                    false,
                    field.reason(),
                    metadata
            ));
        }
        return List.copyOf(actions);
    }

    private void executeSuggestion(DesktopFormSuggestion suggestion) {
        if (suggestion.actions().isEmpty()) {
            String source = normalizeFillSource(textValue(suggestion.metadata().get("fillSource")));
            if (FILL_SOURCE_CHATGPT.equals(source)) {
                if (!aiFillInFlight.compareAndSet(false, true)) {
                    ui.setOverlayMessage("Запрос ChatGPT Plus уже выполняется…");
                    return;
                }
                try {
                    DesktopSnapshot snapshot = currentSnapshot != null
                            && formSignature(currentSnapshot.context()).equals(suggestion.signature())
                            && isSnapshotFresh(currentSnapshot)
                                    ? currentSnapshot
                                    : suggestion.snapshot();
                    if (snapshot == null || !isSnapshotFresh(snapshot)) {
                        aiFillInFlight.set(false);
                        ui.showFillError("Форма устарела. Откройте её снова и повторите заполнение через ChatGPT Plus.");
                        return;
                    }

                    DesktopFormSuggestion published = buildSuggestion(
                            suggestion.signature(),
                            snapshot,
                            FILL_SOURCE_CHATGPT
                    );
                    String relayId = textValue(published.metadata().get("relayId"));
                    if (relayId.isBlank()) {
                        aiFillInFlight.set(false);
                        ui.showFillError("SpringSuite не смог создать ChatGPT Plus relay для активной формы.");
                        return;
                    }

                    String relayPrompt = chatGptPlusPrompt(relayId);
                    browserDomCommandService.enqueueChatGptPlusRelay(snapshot, relayId, relayPrompt);
                    currentSuggestion = published;
                    telemetry.event("desktop_agent", "chatgpt_plus", "dispatched");
                    updateState("chatgpt_plus_dispatched", "Запрос отправляется в текущий ChatGPT Plus-чат.");
                    ui.setOverlayMessage("Ожидаю ответ ChatGPT Plus и локальную проверку значения…");
                    awaitChatGptPlusRelay(
                            suggestion.signature(),
                            snapshot,
                            relayId,
                            Instant.now().plusSeconds(150)
                    );
                } catch (Exception ex) {
                    aiFillInFlight.set(false);
                    failAction("chatgpt_plus_dispatch_failed", safeMessage(ex));
                }
                return;
            }
            if (FILL_SOURCE_AI.equals(source)) {
                if (!aiFillInFlight.compareAndSet(false, true)) {
                    ui.setOverlayMessage("AI-запрос уже выполняется…");
                    return;
                }
                try {
                    DesktopSnapshot snapshot = currentSnapshot != null
                            && formSignature(currentSnapshot.context()).equals(suggestion.signature())
                            && isSnapshotFresh(currentSnapshot)
                                    ? currentSnapshot
                                    : suggestion.snapshot();
                    if (snapshot == null || !isSnapshotFresh(snapshot)) {
                        ui.showFillError("Форма устарела. Выполните повторное сканирование перед AI-заполнением.");
                        return;
                    }

                    updateState("ai_fill_loading", "ИИ формирует значение по prompt активного поля.");
                    DesktopFormSuggestion generated = buildSuggestion(
                            suggestion.signature(),
                            snapshot,
                            FILL_SOURCE_AI,
                            true
                    );

                    DesktopSnapshot latest = freshExternalSnapshot();
                    if (latest != null
                            && formSignature(latest.context()).equals(suggestion.signature())
                            && isSnapshotFresh(latest)) {
                        generated = refreshSuggestionSnapshot(generated, latest);
                    }
                    currentSuggestion = generated;

                    if (generated.actions().isEmpty()) {
                        updateState("ai_fill_empty", generated.summary());
                        showSuggestionOverlay(generated);
                        return;
                    }

                    updateState("ai_fill_ready", "ИИ подготовил значение; выполняется заполнение активного поля.");
                    executeSuggestion(generated);
                } catch (Exception ex) {
                    failAction("ai_fill_failed", safeMessage(ex));
                } finally {
                    aiFillInFlight.set(false);
                }
                return;
            }
            ui.showTransientMessage(
                    "В памяти нет значения для активного поля. Добавьте его в autofill-profile или выберите «От ИИ»."
            );
            return;
        }
        if (isBrowserDomSnapshot(suggestion.snapshot())) {
            try {
                BrowserDomModels.BrowserDomFillCommand command = browserDomCommandService.enqueue(
                        suggestion.snapshot(),
                        suggestion.actions()
                );
                actionExecutionCount.incrementAndGet();
                telemetry.event("desktop_agent", "action_execution", "browser_dom_success");
                if (FILL_SOURCE_CHATGPT.equals(normalizeFillSource(textValue(suggestion.metadata().get("fillSource"))))) {
                    chatGptRelayService.markConsumed(suggestion.signature());
                }
                suppress(suggestion.signature());
                updateState("browser_dom_command_queued", "Команда заполнения передана расширению для " + command.fields().size() + " полей.");
                ui.showFillSuccess("Готово: значение получено и передано активному полю без отправки формы.");
            } catch (Exception ex) {
                failAction("browser_dom_command_failed", safeMessage(ex));
            }
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
            telemetry.event("desktop_agent", "action_execution", "native_success");
            if (FILL_SOURCE_CHATGPT.equals(normalizeFillSource(textValue(suggestion.metadata().get("fillSource"))))) {
                chatGptRelayService.markConsumed(suggestion.signature());
            }
            suppress(suggestion.signature());
            updateState("action_executed", "Заполнено " + suggestion.actions().size() + " полей в активной форме.");
            ui.notifyInfo("SpringSuite", "Заполнено полей: " + suggestion.actions().size());
            ui.showFillSuccess("Готово: заполнено полей — " + suggestion.actions().size() + ".");
        } catch (Exception ex) {
            failAction("action_failed", safeMessage(ex));
        }
    }

    private String chatGptPlusPrompt(String relayId) {
        return "SpringSuite Plus Relay " + relayId + ". "
                + "Используй подключённый NorthStar MCP. Сначала выполни form-relay current, "
                + "сформируй краткое безопасное значение только для сфокусированного обычного поля "
                + "по его placeholder или contextPrompt, затем передай результат через form-relay submit "
                + relayId + " <base64url-json>. Не заполняй пароли, коды, персональные, банковские, "
                + "медицинские или государственные данные. Не отправляй форму и не задавай уточняющих вопросов.";
    }

    private void awaitChatGptPlusRelay(
            String signature,
            DesktopSnapshot fallbackSnapshot,
            String relayId,
            Instant deadline
    ) {
        if (!aiFillInFlight.get()) {
            return;
        }
        if (Instant.now().isAfter(deadline)) {
            aiFillInFlight.set(false);
            telemetry.event("desktop_agent", "chatgpt_plus", "timeout");
            updateState("chatgpt_plus_timeout", "ChatGPT Plus не вернул relay-значение за отведённое время.");
            ui.showFillError("ChatGPT Plus не ответил за 150 секунд. Проверьте, что открыт авторизованный ChatGPT-таб, и повторите.");
            return;
        }

        if (chatGptRelayService.readyFor(signature).isPresent()) {
            DesktopSnapshot snapshot = freshExternalSnapshot();
            if (snapshot == null || !formSignature(snapshot.context()).equals(signature)) {
                snapshot = fallbackSnapshot;
            }
            if (snapshot == null || !isSnapshotFresh(snapshot)) {
                aiFillInFlight.set(false);
                ui.showFillError("ChatGPT Plus вернул значение, но форма уже устарела. Повторите запрос на актуальном поле.");
                return;
            }

            DesktopFormSuggestion generated = buildSuggestion(signature, snapshot, FILL_SOURCE_CHATGPT);
            currentSuggestion = generated;
            if (generated.actions().isEmpty()) {
                aiFillInFlight.set(false);
                updateState("chatgpt_plus_no_safe_value", generated.summary());
                ui.showFillError(generated.summary());
                return;
            }

            aiFillInFlight.set(false);
            telemetry.event("desktop_agent", "chatgpt_plus", "ready");
            updateState("chatgpt_plus_ready", "ChatGPT Plus вернул значение; выполняется безопасное заполнение.");
            executeSuggestion(generated);
            return;
        }

        scheduler.schedule(
                () -> awaitChatGptPlusRelay(signature, fallbackSnapshot, relayId, deadline),
                700L,
                TimeUnit.MILLISECONDS
        );
    }

    private void showHints(DesktopFormSuggestion suggestion) {
        DesktopHintResponse hints = suggestion.hints();
        if (hints == null || hints.hints().isEmpty()) {
            ui.setOverlayMessage("Дополнительных подсказок для этой формы нет.");
            return;
        }
        String message = hints.hints().stream()
                .map(hint -> hint.title() + ": " + hint.message())
                .reduce((left, right) -> left + " В· " + right)
                .orElse(hints.summary());
        ui.setOverlayMessage(message);
        ui.notifyInfo("SpringSuite · подсказки формы", message);
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
        return stableFormSignature(context);
    }

    static String stableFormSignature(DesktopFocusContext context) {
        StringBuilder material = new StringBuilder()
                .append(context.activeApplication()).append('|')
                .append(context.url()).append('|')
                .append(context.form().id()).append('|')
                .append(context.form().name()).append('|')
                .append(context.form().action()).append('|')
                .append(context.form().method()).append('|');
        for (DesktopFormField field : context.form().fields()) {
            material.append(field.id()).append(':')
                    .append(field.name()).append(':')
                    .append(field.type()).append(':')
                    .append(field.required()).append(':')
                    .append(field.sensitive()).append('|');
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
        telemetry.event("desktop_agent", "action_execution", "failed");
        updateState(code, message);
        ui.notifyWarning("SpringSuite · действие заблокировано", message);
        ui.showFillError("Действие не выполнено: " + message);
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

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
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
