package com.takesome.springsuite.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.takesome.springsuite.core.ai.AiChatRequest;
import com.takesome.springsuite.core.ai.AiChatResponse;
import com.takesome.springsuite.core.ai.AiGenerationOptions;
import com.takesome.springsuite.core.ai.AiMessage;
import com.takesome.springsuite.core.ai.AiService;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopActionContract;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopCapabilitySchema;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopContextAnalysis;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFieldPlan;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFocusContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormContext;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormField;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormFillPlan;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFormFillRequest;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopHelperStatus;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopHint;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopHintRequest;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopHintResponse;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopIntegrationSurface;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopSafetyRule;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopWorkflow;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.observability.SuiteTelemetry;
import com.takesome.springsuite.toolbelt.ToolDescriptor;
import com.takesome.springsuite.toolbelt.ToolbeltService;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DesktopHelperService {
    private static final String SOURCE = "desktop-helper";
    private static final String SYSTEM_PROMPT = """
            You are SpringSuite Desktop Helper. Act as a local-first desktop assistant.
            Produce concise, operator-safe guidance for the visible desktop context.
            Never ask to submit forms automatically. Never invent personal data.
            Treat passwords, tokens, financial fields, government identifiers and medical fields as review-only.
            Prefer concrete field-level hints and validation notes.
            """;

    private final DesktopHelperProperties properties;
    private final AiService aiService;
    private final ToolbeltService toolbeltService;
    private final ObjectMapper objectMapper;
    private final OperatorLogService logService;
    private final SuiteTelemetry telemetry;
    private final Cache<String, DesktopFormFillPlan> deterministicPlanCache;

    public DesktopHelperService(
            DesktopHelperProperties properties,
            AiService aiService,
            ToolbeltService toolbeltService,
            ObjectMapper objectMapper,
            OperatorLogService logService,
            SuiteTelemetry telemetry
    ) {
        this.properties = properties;
        this.aiService = aiService;
        this.toolbeltService = toolbeltService;
        this.objectMapper = objectMapper;
        this.logService = logService;
        this.telemetry = telemetry;
        this.deterministicPlanCache = Caffeine.newBuilder()
                .maximumSize(256)
                .expireAfterWrite(Duration.ofSeconds(2))
                .recordStats()
                .build();
        telemetry.registerGauge(
                "form_intelligence",
                "deterministic_plan_cache_size",
                () -> (int) Math.min(Integer.MAX_VALUE, deterministicPlanCache.estimatedSize())
        );
    }

    public DesktopHelperStatus status() {
        Optional<ToolDescriptor> captureTool = toolbeltService.find(properties.getCaptureToolId());
        LinkedHashMap<String, Object> policy = new LinkedHashMap<>();
        policy.put("requireApprovalForWriteActions", properties.isRequireApprovalForWriteActions());
        policy.put("allowDesktopCapture", properties.isAllowDesktopCapture());
        policy.put("allowClipboardRead", properties.isAllowClipboardRead());
        policy.put("allowClipboardWrite", properties.isAllowClipboardWrite());
        policy.put("allowFormFillPlanning", properties.isAllowFormFillPlanning());
        policy.put("allowAutofillExecution", properties.isAllowAutofillExecution());
        policy.put("allowSubmitActions", properties.isAllowSubmitActions());
        policy.put("executorAllowedRealInput", properties.getExecutor().isAllowedRealInput());
        policy.put("bridgeAllowedRealInput", properties.getBridge().isAllowedRealInput());
        policy.put("contextTtl", properties.getContextTtl().toString());
        policy.put("approvalTokenTtlSeconds", properties.getApprovalTokenTtlSeconds());
        policy.put("maxApprovalTokenTtlSeconds", properties.getMaxApprovalTokenTtlSeconds());
        policy.put("maxScreenTextChars", properties.getMaxScreenTextChars());
        policy.put("maxSuggestionCount", properties.getMaxSuggestionCount());
        policy.put("deterministicPlanCacheSize", deterministicPlanCache.estimatedSize());
        policy.put("deterministicPlanCacheHitRate", deterministicPlanCache.stats().hitRate());

        return new DesktopHelperStatus(
                properties.isEnabled(),
                properties.getMode(),
                properties.isAiEnrichmentEnabled(),
                captureTool.map(ToolDescriptor::available).orElse(false),
                properties.getCaptureToolId(),
                endpoints(),
                enabledSurfaceIds(),
                policy
        );
    }

    public DesktopCapabilitySchema schema() {
        LinkedHashMap<String, Object> sidecarContract = new LinkedHashMap<>();
        sidecarContract.put("captureToolId", properties.getCaptureToolId());
        sidecarContract.put("captureToolAvailable", toolbeltService.find(properties.getCaptureToolId()).map(ToolDescriptor::available).orElse(false));
        sidecarContract.put("contextSnapshotInput", List.of("activeWindow", "focusedElement", "selectedText", "screenText", "formFields"));
        sidecarContract.put("contextSnapshotOutput", List.of("DesktopFocusContext"));
        sidecarContract.put("snapshotBridgeEndpoints", List.of(
                "POST /api/desktop-helper/context/capture",
                "POST /api/desktop-helper/context/ingest",
                "GET /api/desktop-helper/context/current",
                "GET /api/desktop-helper/context/latest",
                "POST /api/desktop-helper/browser-dom/snapshot",
                "GET /api/desktop-helper/browser-dom/status",
                "GET /api/desktop-helper/browser-dom/commands/next",
                "POST /api/desktop-helper/browser-dom/commands/{commandId}/ack"
        ));
        sidecarContract.put("approvalEndpoints", List.of("POST /api/desktop-helper/approvals", "POST /api/desktop-helper/actions/dry-run", "POST /api/desktop-helper/actions/execute"));
        sidecarContract.put("executorRegistryEndpoints", List.of("GET /api/desktop-helper/executors", "GET /api/desktop-helper/executors/{id}", "GET /api/desktop-helper/executors/policy"));
        sidecarContract.put("bridgeRegistryEndpoints", List.of("GET /api/desktop-helper/bridges", "GET /api/desktop-helper/bridges/{id}", "GET /api/desktop-helper/bridges/policy"));
        sidecarContract.put("realInputSelfTestEndpoint", "POST /api/desktop-helper/real-input/self-test");
        sidecarContract.put("executorContract", List.of("DesktopActionExecutor", "DesktopActionExecutorRegistry", "NoopDesktopActionExecutor", "RealDesktopActionExecutor", "ExecutionGuardService", "ExecutionAuditService"));
        sidecarContract.put("bridgeContract", List.of("DesktopBridgeAdapter", "DesktopBridgeAdapterRegistry", "ClipboardBridgeAdapter", "KeyboardBridgeAdapter", "MouseBridgeAdapter", "BrowserDomBridgeAdapter", "WindowsUiAutomationBridgeAdapter"));
        sidecarContract.put("browserDomContract", Map.of(
                "mode", "recognition plus operator-confirmed insertion",
                "transport", "Manifest V3 extension to token-protected loopback HTTP endpoints",
                "pageValues", "existing page values are never transmitted; only valuePresent is accepted",
                "proposedValues", "only non-sensitive values shown in the desktop overlay are sent back after the operator clicks «Заполнить»",
                "writeActions", "fill/select/check/uncheck through a short-lived page-bound command",
                "submitActions", "disabled"
        ));
        sidecarContract.put("writeActions", "disabled by default; real input requires executor.allowed-real-input=true, bridge.allowed-real-input=true, enabled real executor, enabled bridge, approval token, dry-run pass, fresh snapshot and audit logging.");

        return new DesktopCapabilitySchema(
                "suite-desktop-helper",
                properties.isEnabled(),
                properties.getMode(),
                surfaces(),
                safetyRules(),
                workflows(),
                actionContracts(),
                sidecarContract
        );
    }

    public DesktopContextAnalysis analyze(DesktopFocusContext context) {
        DesktopFocusContext safeContext = context == null ? DesktopFocusContext.empty() : context;
        if (!properties.isEnabled()) {
            return new DesktopContextAnalysis(false, "Desktop helper is disabled.", "disabled", 0, 0, 0, "", List.of(), List.of("suite.desktop-helper.enabled=false"), Map.of());
        }

        DesktopFormContext form = safeContext.form();
        List<DesktopFormField> fields = form.fields();
        int required = 0;
        int sensitive = 0;
        String focusedFieldId = "";
        for (int i = 0; i < fields.size(); i++) {
            DesktopFormField field = fields.get(i);
            if (field.required()) {
                required++;
            }
            if (isSensitive(field)) {
                sensitive++;
            }
            if (field.focused()) {
                focusedFieldId = fieldId(field, i);
            }
        }

        ArrayList<String> actions = new ArrayList<>();
        actions.add("POST /api/desktop-helper/hints");
        if (!fields.isEmpty()) {
            actions.add("POST /api/desktop-helper/form-fill/plan");
        }
        if (fields.isEmpty() && !safeContext.screenText().isBlank()) {
            actions.add("Request a field detector from the desktop-capture sidecar before planning autofill.");
        }

        ArrayList<String> warnings = new ArrayList<>();
        if (sensitive > 0) {
            warnings.add("Sensitive fields detected; keep them review-only and do not autofill secrets without explicit approval.");
        }
        if (!safeContext.clipboardPreview().isBlank() && !properties.isAllowClipboardRead()) {
            warnings.add("Clipboard preview was provided while clipboard read is disabled by policy; treat it as untrusted context.");
        }
        if (!properties.isAllowAutofillExecution()) {
            warnings.add("Autofill execution is disabled; this module will produce plans and hints only.");
        }

        String risk = sensitive > 0 ? "high" : required > 0 ? "medium" : "low";
        String summary = fields.isEmpty()
                ? "Desktop context captured without a structured form."
                : "Detected " + fields.size() + " form field(s), " + required + " required and " + sensitive + " sensitive.";

        return new DesktopContextAnalysis(
                true,
                summary,
                risk,
                fields.size(),
                required,
                sensitive,
                focusedFieldId,
                actions,
                warnings,
                Map.of(
                        "activeApplication", safeContext.activeApplication(),
                        "activeWindowTitle", safeContext.activeWindowTitle(),
                        "url", safeContext.url()
                )
        );
    }

    public DesktopHintResponse hints(DesktopHintRequest request) {
        DesktopHintRequest safeRequest = request == null ? new DesktopHintRequest(DesktopFocusContext.empty(), "", "en-US", Map.of()) : request;
        if (!properties.isEnabled()) {
            return new DesktopHintResponse(false, "Desktop helper is disabled.", List.of(), "", Map.of("code", "desktop_helper_disabled"));
        }

        DesktopFocusContext context = safeRequest.context();
        DesktopFormContext form = context.form();
        ArrayList<DesktopHint> hints = new ArrayList<>();

        if (context.activeWindowTitle().isBlank() && context.screenText().isBlank() && form.fields().isEmpty()) {
            hints.add(new DesktopHint(
                    "capture",
                    "Capture desktop context",
                    "No active window, visible text or form fields were provided. Request a fresh desktop-capture snapshot before asking for help.",
                    "",
                    "info",
                    0.95,
                    Map.of()
            ));
        }

        for (int i = 0; i < form.fields().size() && (properties.getMaxSuggestionCount() <= 0 || hints.size() < properties.getMaxSuggestionCount()); i++) {
            DesktopFormField field = form.fields().get(i);
            String id = fieldId(field, i);
            if (isSensitive(field)) {
                hints.add(new DesktopHint(
                        "safety",
                        "Review sensitive field",
                        "`" + field.displayName() + "` looks sensitive. Keep it manual or require explicit operator approval before any write action.",
                        id,
                        "warning",
                        0.9,
                        Map.of("type", field.type())
                ));
                continue;
            }
            if (field.required() && !fieldHasValue(field) && fieldAvailabilityIssue(field).isBlank()) {
                hints.add(new DesktopHint(
                        "validation",
                        "Required field is empty",
                        "`" + field.displayName() + "` is required. Add it to the fill plan or ask the operator for the missing value.",
                        id,
                        "info",
                        0.82,
                        Map.of("type", field.type())
                ));
            }
            if (field.focused() && !field.placeholder().isBlank()) {
                hints.add(new DesktopHint(
                        "focused-field",
                        "Use placeholder as schema hint",
                        "The focused field placeholder is `" + field.placeholder() + "`; use it to infer expected formatting, not as a value.",
                        id,
                        "info",
                        0.72,
                        Map.of("placeholder", field.placeholder())
                ));
            }
        }

        if (!context.url().isBlank() && (properties.getMaxSuggestionCount() <= 0 || hints.size() < properties.getMaxSuggestionCount())) {
            hints.add(new DesktopHint(
                    "context",
                    "Use page URL as intent context",
                    "The current URL can help classify the workflow before suggesting form actions.",
                    "",
                    "info",
                    0.65,
                    Map.of("url", context.url())
            ));
        }

        if (hints.isEmpty()) {
            hints.add(new DesktopHint(
                    "ready",
                    "Context is ready",
                    "No immediate validation issue was detected. Generate a fill plan before any write action.",
                    "",
                    "info",
                    0.7,
                    Map.of()
            ));
        }

        boolean skipAi = metadataBoolean(safeRequest.preferences().get("skipAi"), false);
        String aiSuggestion = skipAi ? "" : askAi("desktop hints", Map.of(
                "goal", safeRequest.userGoal(),
                "locale", safeRequest.locale(),
                "context", aiSafeContext(context)
        ));

        logService.append(OperatorLogLevel.INFO, SOURCE, "desktop hints generated", Map.of(
                "hints", hints.size(),
                "fields", form.fields().size(),
                "ai", !aiSuggestion.isBlank()
        ));

        return new DesktopHintResponse(true, "Generated " + hints.size() + " desktop helper hint(s).", limit(hints, properties.getMaxSuggestionCount()), aiSuggestion, Map.of(
                "fieldCount", form.fields().size(),
                "surfaceMode", properties.getMode()
        ));
    }

    public DesktopFormFillPlan planFormFill(DesktopFormFillRequest request) {
        SuiteTelemetry.Operation operation = telemetry.start("form_intelligence", "plan_local");
        try {
            String key = deterministicPlanCacheKey(request);
            if (!key.isBlank()) {
                DesktopFormFillPlan cached = deterministicPlanCache.getIfPresent(key);
                if (cached != null) {
                    telemetry.event("form_intelligence", "plan_cache", "hit");
                    operation.success();
                    return cached;
                }
                telemetry.event("form_intelligence", "plan_cache", "miss");
            }
            DesktopFormFillPlan result = planFormFillInternal(request, true);
            if (!key.isBlank() && result.ok()) {
                deterministicPlanCache.put(key, result);
            }
            operation.success();
            return result;
        } catch (RuntimeException ex) {
            operation.failure("exception");
            throw ex;
        }
    }

    public DesktopFormFillPlan planFormFillExternal(DesktopFormFillRequest request) {
        SuiteTelemetry.Operation operation = telemetry.start("form_intelligence", "plan_external");
        try {
            DesktopFormFillPlan result = planFormFillInternal(request, false);
            operation.success();
            return result;
        } catch (RuntimeException ex) {
            operation.failure("exception");
            throw ex;
        }
    }

    public DesktopFormFillPlan planFormFillWithAi(DesktopFormFillRequest request) {
        SuiteTelemetry.Operation operation = telemetry.start("form_intelligence", "plan_ai");
        try {
            DesktopFormFillPlan result = planFormFillWithAiInternal(request);
            operation.success();
            return result;
        } catch (RuntimeException ex) {
            operation.failure("exception");
            throw ex;
        }
    }

    private DesktopFormFillPlan planFormFillWithAiInternal(DesktopFormFillRequest request) {
        DesktopFormFillRequest safeRequest = request == null
                ? new DesktopFormFillRequest(DesktopFocusContext.empty(), "", "en-US", Map.of(), Map.of(), false)
                : request;
        AiProfileResult generated = generateAiProfile(safeRequest);
        DesktopFormFillRequest generatedRequest = new DesktopFormFillRequest(
                safeRequest.context(),
                safeRequest.userGoal(),
                safeRequest.locale(),
                generated.values(),
                safeRequest.constraints(),
                false
        );
        DesktopFormFillPlan plan = planFormFillInternal(generatedRequest, false);

        ArrayList<String> warnings = new ArrayList<>(plan.warnings());
        warnings.addAll(generated.warnings());
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(plan.metadata());
        metadata.put("fillSource", "ai");
        metadata.put("aiProvider", generated.providerId());
        metadata.put("aiModel", generated.model());
        metadata.put("aiGeneratedValueCount", generated.values().size());
        metadata.put("aiWarning", generated.warnings().stream().findFirst().orElse(""));

        String summary = generated.values().isEmpty()
                ? "ИИ не предложил безопасных значений для распознанных полей."
                : "ИИ подготовил " + generated.values().size() + " безопасных значений; проверьте их перед заполнением.";
        return new DesktopFormFillPlan(
                plan.ok(),
                summary,
                plan.fields(),
                warnings,
                plan.requiresApproval(),
                "",
                metadata
        );
    }

    private String deterministicPlanCacheKey(DesktopFormFillRequest request) {
        if (request == null) {
            return "";
        }
        try {
            byte[] payload = objectMapper.writeValueAsBytes(request);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return HexFormat.of().formatHex(digest);
        } catch (Exception ignored) {
            return "";
        }
    }

    private DesktopFormFillPlan planFormFillInternal(DesktopFormFillRequest request, boolean includeAiReview) {
        DesktopFormFillRequest safeRequest = request == null ? new DesktopFormFillRequest(DesktopFocusContext.empty(), "", "en-US", Map.of(), Map.of(), false) : request;
        if (!properties.isEnabled()) {
            return new DesktopFormFillPlan(false, "Desktop helper is disabled.", List.of(), List.of("suite.desktop-helper.enabled=false"), false, "", Map.of("code", "desktop_helper_disabled"));
        }
        if (!properties.isAllowFormFillPlanning()) {
            return new DesktopFormFillPlan(false, "Form fill planning is disabled by policy.", List.of(), List.of("suite.desktop-helper.allow-form-fill-planning=false"), false, "", Map.of("code", "form_fill_planning_disabled"));
        }

        DesktopFormContext form = safeRequest.context().form();
        if (form.fields().isEmpty()) {
            return new DesktopFormFillPlan(false, "No structured form fields were supplied.", List.of(), List.of("Request a desktop-capture or browser-form snapshot before planning."), false, "", Map.of("code", "form_fields_missing"));
        }

        ArrayList<DesktopFieldPlan> plans = new ArrayList<>();
        int fillCount = 0;
        int reviewCount = 0;
        int askCount = 0;
        int sensitiveCount = 0;

        for (int i = 0; i < form.fields().size(); i++) {
            DesktopFormField field = form.fields().get(i);
            String id = fieldId(field, i);
            boolean sensitive = isSensitive(field);
            if (sensitive) {
                sensitiveCount++;
            }
            FieldMatch match = matchFieldValue(field, safeRequest.profile(), safeRequest.constraints());
            DesktopFieldPlan plan;

            String availabilityIssue = fieldAvailabilityIssue(field);
            if (!availabilityIssue.isBlank()) {
                plan = new DesktopFieldPlan(
                        id,
                        field.displayName(),
                        "leave",
                        "",
                        0.98,
                        availabilityIssue,
                        sensitive,
                        false,
                        Map.of("source", "field-state")
                );
            } else if (fieldHasValue(field)) {
                plan = new DesktopFieldPlan(
                        id,
                        field.displayName(),
                        "leave",
                        "",
                        0.88,
                        "Field already has a value; do not overwrite without operator intent.",
                        sensitive,
                        false,
                        Map.of("source", "existing-value")
                );
            } else if (sensitive && !safeRequest.allowSensitiveSuggestions()) {
                reviewCount++;
                plan = new DesktopFieldPlan(
                        id,
                        field.displayName(),
                        "review",
                        "",
                        0.9,
                        "Sensitive field; hidden or high-risk values stay manual by default.",
                        true,
                        true,
                        Map.of("source", "safety-policy")
                );
            } else if (!match.value().isBlank()) {
                fillCount++;
                String action = writeActionFor(field);
                boolean needsReview = sensitive || !optionsAccept(field, match.value());
                if (needsReview) {
                    reviewCount++;
                }
                plan = new DesktopFieldPlan(
                        id,
                        field.displayName(),
                        action,
                        sensitive ? "" : match.value(),
                        needsReview ? 0.62 : match.confidence(),
                        needsReview
                                ? "Candidate value found from " + match.source() + ", but the field needs operator review."
                                : "Candidate value found from " + match.source() + ".",
                        sensitive,
                        needsReview,
                        Map.of("source", match.source())
                );
            } else if (field.required()) {
                askCount++;
                plan = new DesktopFieldPlan(
                        id,
                        field.displayName(),
                        "ask",
                        "",
                        0.55,
                        "Required field has no safe value in profile or constraints.",
                        sensitive,
                        true,
                        Map.of("source", "missing")
                );
            } else {
                plan = new DesktopFieldPlan(
                        id,
                        field.displayName(),
                        "leave",
                        "",
                        0.6,
                        "Optional field without a safe candidate value.",
                        sensitive,
                        false,
                        Map.of("source", "optional")
                );
            }
            plans.add(plan);
        }

        ArrayList<String> warnings = new ArrayList<>();
        if (sensitiveCount > 0) {
            warnings.add("Sensitive fields are review-only unless the request explicitly allows sensitive suggestions; raw sensitive values are not returned in the plan.");
        }
        if (!properties.isAllowAutofillExecution()) {
            warnings.add("Autofill execution is disabled; use this as an operator-reviewed plan only.");
        }
        if (properties.isRequireApprovalForWriteActions()) {
            warnings.add("Any write action requires explicit approval before execution.");
        }
        if (askCount > 0) {
            warnings.add(askCount + " required field(s) still need operator input.");
        }

        String aiSuggestion = includeAiReview
                ? askAi("form fill plan review", Map.of(
                        "goal", safeRequest.userGoal(),
                        "locale", safeRequest.locale(),
                        "context", aiSafeContext(safeRequest.context()),
                        "profileKeys", sortedKeys(safeRequest.profile()),
                        "constraintKeys", sortedKeys(safeRequest.constraints()),
                        "plan", aiSafePlan(plans)
                ))
                : "";

        boolean requiresApproval = properties.isRequireApprovalForWriteActions() && fillCount > 0;
        String summary = "Planned " + plans.size() + " field action(s): " + fillCount + " fill/select, " + reviewCount + " review, " + askCount + " ask.";
        logService.append(OperatorLogLevel.INFO, SOURCE, "desktop form fill plan generated", Map.of(
                SuiteTelemetry.CORRELATION_ID, stringValue(
                        safeRequest.context().metadata().get(SuiteTelemetry.CORRELATION_ID)
                ),
                "fields", plans.size(),
                "fill", fillCount,
                "review", reviewCount,
                "ask", askCount,
                "sensitive", sensitiveCount,
                "requiresApproval", requiresApproval
        ));

        return new DesktopFormFillPlan(
                true,
                summary,
                plans,
                warnings,
                requiresApproval,
                aiSuggestion,
                Map.of(
                        "formId", form.id(),
                        "formName", form.name(),
                        "executionEnabled", properties.isAllowAutofillExecution(),
                        SuiteTelemetry.CORRELATION_ID, stringValue(
                                safeRequest.context().metadata().get(SuiteTelemetry.CORRELATION_ID)
                        )
                )
        );
    }

    private AiProfileResult generateAiProfile(DesktopFormFillRequest request) {
        if (!properties.isAiEnrichmentEnabled()) {
            return new AiProfileResult(
                    Map.of(),
                    "",
                    "",
                    List.of("Заполнение от ИИ отключено: suite.desktop-helper.ai-enrichment-enabled=false")
            );
        }

        LinkedHashMap<String, DesktopFormField> eligibleFields = new LinkedHashMap<>();
        ArrayList<Map<String, Object>> fieldSchemas = new ArrayList<>();
        DesktopFormContext form = request.context().form();
        boolean focusedEligible = form.fields().stream()
                .anyMatch(field -> field.focused() && isAiFillEligible(field));
        for (int index = 0; index < form.fields().size(); index++) {
            DesktopFormField field = form.fields().get(index);
            if (!isAiFillEligible(field) || (focusedEligible && !field.focused())) {
                continue;
            }
            String id = fieldId(field, index);
            eligibleFields.put(id, field);

            LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
            schema.put("fieldId", id);
            schema.put("label", field.displayName());
            schema.put("type", field.type());
            schema.put("required", field.required());
            schema.put("placeholder", field.placeholder());
            schema.put("contextPrompt", stringValue(field.metadata().get("contextPrompt")));
            schema.put("prompt", firstNonBlank(
                    field.placeholder(),
                    stringValue(field.metadata().get("contextPrompt")),
                    field.label()
            ));
            schema.put("focused", field.focused());
            schema.put("options", field.options());
            fieldSchemas.add(Map.copyOf(schema));
        }

        if (eligibleFields.isEmpty()) {
            return new AiProfileResult(
                    Map.of(),
                    "",
                    "",
                    List.of("ИИ не нашёл обычных текстовых полей, для которых допустимо генерировать содержание.")
            );
        }

        String systemPrompt = """
                You generate operator-reviewed draft values for visible web-form fields.
                Return JSON only with this exact shape: {"fields":[{"fieldId":"...","value":"..."}]}.
                Use only fieldId values supplied by the user message.
                Treat each field prompt as the instruction or query that the value must answer.
                Generate concise content in the requested locale using prompt, placeholder, contextPrompt and label.
                For search fields, return a useful concise search query rather than an explanation.
                When a focused field is supplied, generate only that field.
                Never generate or infer names, usernames, email addresses, phone numbers, postal addresses,
                company identity, dates of birth, passwords, passcodes, tokens, secrets, payment details,
                banking data, government identifiers, medical data or authentication data.
                Never propose submit, click or navigation actions.
                Omit a field when information is insufficient or when filling it would require personal facts.
                For select fields, value must exactly equal one supplied option.
                """;

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("locale", request.locale());
        payload.put("goal", request.userGoal());
        payload.put("pageTitle", request.context().activeWindowTitle());
        payload.put("pageUrl", request.context().url());
        payload.put("focusedFieldOnly", focusedEligible);
        payload.put("fields", fieldSchemas);

        try {
            AiChatResponse response = aiService.chat(new AiChatRequest(
                    properties.getAiFillProvider(),
                    properties.getAiFillModel(),
                    List.of(
                            AiMessage.system(systemPrompt),
                            AiMessage.user(objectMapper.writeValueAsString(payload))
                    ),
                    new AiGenerationOptions(1200, 0.2, null, false, "", null, false, Map.of()),
                    List.of(),
                    Map.of("desktopHelperTask", "ai-form-fill")
            ));
            if (!response.ok()) {
                return new AiProfileResult(
                        Map.of(),
                        response.providerId(),
                        response.model(),
                        List.of(aiFailureMessage(response))
                );
            }

            JsonNode root = objectMapper.readTree(extractJsonObject(response.outputText()));
            JsonNode fields = root.path("fields");
            if (!fields.isArray()) {
                return new AiProfileResult(
                        Map.of(),
                        response.providerId(),
                        response.model(),
                        List.of("ИИ вернул ответ без массива fields; заполнение заблокировано.")
                );
            }

            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            for (JsonNode item : fields) {
                String fieldId = stringValue(item.path("fieldId").asText()).trim();
                String value = stringValue(item.path("value").asText()).trim();
                DesktopFormField field = eligibleFields.get(fieldId);
                if (field == null || value.isBlank() || value.length() > 4_000 || !isAiFillEligible(field)) {
                    continue;
                }
                if (!optionsAccept(field, value)) {
                    continue;
                }
                values.put(fieldId, value);
            }
            return new AiProfileResult(
                    Map.copyOf(values),
                    response.providerId(),
                    response.model(),
                    values.isEmpty()
                            ? List.of("ИИ не предложил значений, прошедших локальную safety-проверку.")
                            : List.of()
            );
        } catch (RuntimeException | JsonProcessingException ex) {
            return new AiProfileResult(
                    Map.of(),
                    "",
                    "",
                    List.of("Не удалось разобрать ответ ИИ: " + safeError(ex))
            );
        }
    }

    private String aiFailureMessage(AiChatResponse response) {
        String provider = firstNonBlank(response.providerId(), properties.getAiFillProvider(), "AI");
        String model = firstNonBlank(response.model(), properties.getAiFillModel());
        String raw = firstNonBlank(response.errorMessage(), response.errorCode(), "unknown error");
        String normalized = lower(response.errorCode() + " " + response.errorMessage());

        if (normalized.contains("quota")
                || normalized.contains("insufficient_quota")
                || normalized.contains("billing")) {
            return "Квота OpenAI API исчерпана. Модель " + model
                    + " выбрана правильно, но ключ не может выполнить платный запрос. Пополните API billing или укажите другой доступный provider.";
        }
        if (normalized.contains("model_not_found")
                || normalized.contains("does not exist")
                || normalized.contains("not have access to model")) {
            return "Модель " + model + " недоступна для текущего API-проекта " + provider + ".";
        }
        if (normalized.contains("rate_limit") || normalized.contains("too many requests")) {
            return "OpenAI временно ограничил частоту запросов к " + model + ". Повторите попытку позже.";
        }
        if (normalized.contains("authentication")
                || normalized.contains("invalid_api_key")
                || normalized.contains("unauthorized")) {
            return "API-ключ для " + provider + " отклонён. Проверьте credentials перед повтором запроса к " + model + ".";
        }
        return "ИИ " + model + " не сформировал план: " + raw;
    }

    private boolean isAiFillEligible(DesktopFormField field) {
        if (field == null || isSensitive(field) || fieldHasValue(field) || !fieldAvailabilityIssue(field).isBlank()) {
            return false;
        }
        String type = lower(field.type());
        if (!(type.equals("text") || type.equals("textarea") || type.equals("search") || type.startsWith("select"))) {
            return false;
        }

        String key = searchable(field);
        List<String> personalHints = List.of(
                "name", "firstname", "lastname", "surname", "username", "login",
                "email", "mail", "phone", "mobile", "telephone", "address", "street",
                "city", "country", "postal", "postcode", "zipcode", "company", "organization",
                "employer", "birthday", "birthdate", "dateofbirth", "medical", "diagnosis"
        );
        if (personalHints.stream().anyMatch(key::contains)) {
            return false;
        }

        String unicodeKey = lower(String.join(
                " ",
                field.id(),
                field.label(),
                field.name(),
                field.type(),
                field.placeholder(),
                stringValue(field.metadata().get("contextPrompt"))
        ));
        List<String> localizedPersonalHints = List.of(
                "имя", "фамили", "логин", "почт", "телефон", "мобильн", "адрес",
                "улиц", "город", "стран", "индекс", "компани", "организац", "работодател",
                "дата рождения", "медицин", "диагноз", "паспорт", "банк", "карта"
        );
        if (localizedPersonalHints.stream().anyMatch(unicodeKey::contains)) {
            return false;
        }

        String prompt = firstNonBlank(
                stringValue(field.metadata().get("contextPrompt")),
                field.placeholder(),
                field.label()
        );
        String normalizedPrompt = lower(prompt).replaceAll("[^\\p{L}\\p{N}]+", "");
        return !normalizedPrompt.isBlank()
                && !normalizedPrompt.equals("text")
                && !normalizedPrompt.equals("textarea")
                && !normalizedPrompt.equals("field")
                && !normalizedPrompt.startsWith("webfield");
    }

    private String extractJsonObject(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("```")) {
            int newline = value.indexOf('\n');
            int closing = value.lastIndexOf("```");
            if (newline >= 0 && closing > newline) {
                value = value.substring(newline + 1, closing).trim();
            }
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("AI response does not contain a JSON object.");
        }
        return value.substring(start, end + 1);
    }

    private record AiProfileResult(
            Map<String, Object> values,
            String providerId,
            String model,
            List<String> warnings
    ) {
        private AiProfileResult {
            values = values == null ? Map.of() : Map.copyOf(values);
            providerId = providerId == null ? "" : providerId;
            model = model == null ? "" : model;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    private List<DesktopIntegrationSurface> surfaces() {
        ArrayList<DesktopIntegrationSurface> result = new ArrayList<>();
        for (Map.Entry<String, DesktopHelperProperties.Surface> entry : properties.getSurfaces().entrySet()) {
            DesktopHelperProperties.Surface surface = entry.getValue();
            result.add(new DesktopIntegrationSurface(
                    entry.getKey(),
                    titleForSurface(entry.getKey()),
                    surface.getAccess(),
                    surface.isEnabled(),
                    surface.getAdapter(),
                    surface.getCapabilities(),
                    safetyForSurface(entry.getKey(), surface.getAccess())
            ));
        }
        return result;
    }

    private List<DesktopSafetyRule> safetyRules() {
        return List.of(
                new DesktopSafetyRule("no-hidden-writes", "No hidden writes", "The helper may plan desktop writes, but must not type, click, paste or submit without an approval-bearing request.", "controller/service policy"),
                new DesktopSafetyRule("sensitive-review", "Sensitive field review", "Passwords, tokens, payment data, banking details and government identifiers remain manual/review-only by default.", "field classifier"),
                new DesktopSafetyRule("clipboard-gate", "Clipboard gate", "Clipboard read/write is disabled unless explicitly enabled in configuration.", "configuration"),
                new DesktopSafetyRule("redacted-ai-context", "Redacted AI context", "AI enrichment receives field labels and metadata, not raw sensitive values.", "service prompt builder"),
                new DesktopSafetyRule("operator-audit", "Operator audit", "Hints and fill plans are recorded without raw secret values.", "operator log"),
                new DesktopSafetyRule("approval-token", "Approval token required", "Future write actions must pass through a short-lived approval token and dry-run guard before real execution.", "approval service"),
                new DesktopSafetyRule("executor-abstraction", "Executor abstraction", "Desktop execution is routed through DesktopActionExecutor; the default NoopDesktopActionExecutor performs no real input.", "execution guard + executor backend")
        );
    }

    private List<DesktopWorkflow> workflows() {
        return List.of(
                new DesktopWorkflow("desktop.context.snapshot", "Capture desktop context", "A sidecar or browser extension produces DesktopFocusContext from the active window, visible text and focused form.", List.of("GET /api/desktop-helper/schema", "POST /api/desktop-helper/context/capture", "POST /api/desktop-helper/context/ingest", "GET /api/desktop-helper/context/current", "POST /api/desktop-helper/context/analyze"), List.of()),
                new DesktopWorkflow("desktop.form.hints", "Generate form hints", "Analyze focused fields and produce validation, safety and formatting hints.", List.of("POST /api/desktop-helper/hints"), List.of()),
                new DesktopWorkflow("desktop.form.fill-plan", "Plan form filling", "Map safe profile/constraint values to detected fields and return an operator-reviewed plan.", List.of("POST /api/desktop-helper/form-fill/plan"), List.of("write approval for execution")),
                new DesktopWorkflow("desktop.action.approve", "Approve desktop actions", "Issue a short-lived approval token bound to a snapshot and explicit action list.", List.of("POST /api/desktop-helper/approvals", "GET /api/desktop-helper/approvals/{tokenId}"), List.of("operator approval", "fresh snapshot")),
                new DesktopWorkflow("desktop.action.dry-run", "Dry-run approved action", "Validate an approval token and preview guarded desktop actions without typing, clicking, pasting or submitting.", List.of("POST /api/desktop-helper/actions/dry-run"), List.of("approval token", "fresh snapshot", "guard pass")),
                new DesktopWorkflow("desktop.action.execute-stub", "Execute approved action stub", "Consume a token only after a prior dry-run pass and return a simulated execution result without desktop input.", List.of("POST /api/desktop-helper/actions/execute"), List.of("desktop.actions.execute scope", "prior dry-run pass", "fresh snapshot", "audit log")),
                new DesktopWorkflow("desktop.action.execute-approved", "Execute approved action", "Reserved action surface for future keyboard/mouse/clipboard execution after explicit approval and dry-run-backed stub validation.", List.of("future real executor behind /api/desktop-helper/actions/execute"), List.of("explicit approval", "surface enabled", "audit log", "dry-run pass", "execution stub pass"))
        );
    }

    private List<DesktopActionContract> actionContracts() {
        return List.of(
                new DesktopActionContract("read-context", "Read desktop context", "read", "none", List.of("activeWindow", "focusedElement", "screenText"), List.of("DesktopFocusContext")),
                new DesktopActionContract("capture-snapshot", "Capture desktop snapshot", "read", "none", List.of("DesktopCaptureRequest"), List.of("DesktopSnapshotResult")),
                new DesktopActionContract("ingest-snapshot", "Ingest desktop snapshot", "read", "none", List.of("raw sidecar/browser JSON", "DesktopFocusContext"), List.of("DesktopSnapshotResult")),
                new DesktopActionContract("analyze-context", "Analyze desktop context", "read", "none", List.of("DesktopFocusContext"), List.of("DesktopContextAnalysis")),
                new DesktopActionContract("suggest-hints", "Suggest desktop hints", "read", "none", List.of("DesktopHintRequest"), List.of("DesktopHintResponse")),
                new DesktopActionContract("plan-form-fill", "Plan form fill", "read-plan", "none", List.of("DesktopFormFillRequest"), List.of("DesktopFormFillPlan")),
                new DesktopActionContract("issue-approval", "Issue approval token", "approval", "required", List.of("DesktopApprovalRequest", "DesktopFormFillPlan", "DesktopApprovedAction[]"), List.of("DesktopApprovalToken")),
                new DesktopActionContract("dry-run-approved-actions", "Dry-run approved actions", "dry-run", "required", List.of("DesktopActionDryRunRequest", "approvalToken"), List.of("DesktopActionDryRunResult")),
                new DesktopActionContract("list-executors", "List desktop action executors", "read", "none", List.of("DesktopActionExecutorRegistry"), List.of("DesktopActionExecutor.Descriptor[]")),
                new DesktopActionContract("execute-approved-actions-stub", "Execute approved actions stub", "execution-stub", "required", List.of("DesktopActionExecutionRequest", "approvalToken", "dryRunPass", "DesktopActionExecutorRegistry", "DesktopActionExecutor", "ExecutionGuardService", "ExecutionAuditService"), List.of("DesktopActionExecutionResult")),
                new DesktopActionContract("execute-form-fill", "Execute form fill", "write", "required", List.of("DesktopFormFillPlan", "approvalToken", "dryRunPass", "executionStubPass"), List.of("executionResult"))
        );
    }

    private List<String> endpoints() {
        return List.of(
                "GET /api/desktop-helper/status",
                "GET /api/desktop-helper/schema",
                "POST /api/desktop-helper/context/capture",
                "POST /api/desktop-helper/context/ingest",
                "GET /api/desktop-helper/context/latest",
                "GET /api/desktop-helper/context/current",
                "DELETE /api/desktop-helper/context/latest",
                "GET /api/desktop-helper/approvals",
                "GET /api/desktop-helper/approvals/{tokenId}",
                "POST /api/desktop-helper/approvals",
                "POST /api/desktop-helper/actions/dry-run",
                "POST /api/desktop-helper/actions/execute",
                "GET /api/desktop-helper/executors",
                "GET /api/desktop-helper/executors/{id}",
                "GET /api/desktop-helper/executors/policy",
                "GET /api/desktop-helper/bridges",
                "GET /api/desktop-helper/bridges/{id}",
                "GET /api/desktop-helper/bridges/policy",
                "POST /api/desktop-helper/real-input/self-test",
                "GET /api/desktop-helper/browser-dom/status",
                "POST /api/desktop-helper/browser-dom/snapshot",
                "GET /api/desktop-helper/browser-dom/commands/next",
                "POST /api/desktop-helper/browser-dom/commands/{commandId}/ack",
                "POST /api/desktop-helper/context/analyze",
                "POST /api/desktop-helper/hints",
                "POST /api/desktop-helper/form-fill/plan"
        );
    }

    private List<String> enabledSurfaceIds() {
        return properties.getSurfaces().entrySet().stream()
                .filter(entry -> entry.getValue().isEnabled())
                .map(Map.Entry::getKey)
                .toList();
    }

    private String askAi(String task, Map<String, Object> payload) {
        if (!properties.isAiEnrichmentEnabled()) {
            return "";
        }
        try {
            String payloadJson = toJson(payload);
            AiChatRequest request = new AiChatRequest(
                    "",
                    "",
                    List.of(
                            AiMessage.system(SYSTEM_PROMPT),
                            AiMessage.user("Task: " + task + "\n\nSanitized desktop payload:\n" + payloadJson)
                    ),
                    new AiGenerationOptions(900, 0.2, null, false, "", null, false, Map.of()),
                    List.of(),
                    Map.of("desktopHelperTask", task)
            );
            AiChatResponse response = aiService.chat(request);
            if (response.ok()) {
                return truncate(response.outputText(), 4_000);
            }
            logService.append(OperatorLogLevel.DEBUG, SOURCE, "AI enrichment skipped", Map.of(
                    "task", task,
                    "code", response.errorCode(),
                    "message", response.errorMessage()
            ));
        } catch (RuntimeException | JsonProcessingException ex) {
            logService.append(OperatorLogLevel.DEBUG, SOURCE, "AI enrichment failed", Map.of(
                    "task", task,
                    "error", safeError(ex)
            ));
        }
        return "";
    }

    private Map<String, Object> aiSafeContext(DesktopFocusContext context) {
        DesktopFocusContext safeContext = context == null ? DesktopFocusContext.empty() : context;
        LinkedHashMap<String, Object> safe = new LinkedHashMap<>();
        safe.put("platform", safeContext.platform());
        safe.put("activeApplication", safeContext.activeApplication());
        safe.put("activeWindowTitle", safeContext.activeWindowTitle());
        safe.put("url", safeContext.url());
        safe.put("focusedElementRole", safeContext.focusedElementRole());
        safe.put("focusedElementName", safeContext.focusedElementName());
        safe.put("selectedText", truncate(safeContext.selectedText(), 1_000));
        safe.put("screenText", truncate(safeContext.screenText(), properties.getMaxScreenTextChars()));
        safe.put("form", aiSafeForm(safeContext.form()));
        return safe;
    }

    private Map<String, Object> aiSafeForm(DesktopFormContext form) {
        DesktopFormContext safeForm = form == null ? DesktopFormContext.empty() : form;
        ArrayList<Map<String, Object>> fields = new ArrayList<>();
        for (int i = 0; i < safeForm.fields().size(); i++) {
            DesktopFormField field = safeForm.fields().get(i);
            LinkedHashMap<String, Object> safe = new LinkedHashMap<>();
            safe.put("id", fieldId(field, i));
            safe.put("label", field.displayName());
            safe.put("name", field.name());
            safe.put("type", field.type());
            safe.put("required", field.required());
            safe.put("focused", field.focused());
            safe.put("sensitive", isSensitive(field));
            safe.put("valuePresent", fieldHasValue(field));
            safe.put("placeholder", field.placeholder());
            safe.put("options", field.options());
            fields.add(safe);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("id", safeForm.id());
        result.put("name", safeForm.name());
        result.put("action", safeForm.action());
        result.put("method", safeForm.method());
        result.put("fields", fields);
        return result;
    }

    private List<Map<String, Object>> aiSafePlan(List<DesktopFieldPlan> plans) {
        ArrayList<Map<String, Object>> safe = new ArrayList<>();
        for (DesktopFieldPlan plan : plans) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("fieldId", plan.fieldId());
            item.put("label", plan.label());
            item.put("action", plan.action());
            item.put("valuePresent", !plan.value().isBlank());
            item.put("confidence", plan.confidence());
            item.put("reason", plan.reason());
            item.put("sensitive", plan.sensitive());
            item.put("needsUserReview", plan.needsUserReview());
            safe.add(item);
        }
        return safe;
    }

    private String toJson(Object value) throws JsonProcessingException {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        return truncate(json, properties.getMaxScreenTextChars() + 4_000);
    }

    private boolean fieldHasValue(DesktopFormField field) {
        if (field == null) {
            return false;
        }
        if (!field.value().isBlank()) {
            return true;
        }
        return metadataBoolean(field.metadata().get("valuePresent"), false);
    }

    private String fieldAvailabilityIssue(DesktopFormField field) {
        if (field == null) {
            return "Field is unavailable.";
        }
        if (metadataBoolean(field.metadata().get("disabled"), false)) {
            return "Field is disabled and cannot be filled.";
        }
        if (metadataBoolean(field.metadata().get("readOnly"), false)) {
            return "Field is read-only and must not be overwritten.";
        }
        if (!metadataBoolean(field.metadata().get("visible"), true)) {
            return "Field is not visible and is excluded from automatic filling.";
        }
        return "";
    }

    private boolean metadataBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value instanceof String text) {
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "true", "1", "yes", "on" -> true;
                case "false", "0", "no", "off" -> false;
                default -> fallback;
            };
        }
        return fallback;
    }

    private boolean isSensitive(DesktopFormField field) {
        String haystack = searchable(field);
        String type = lower(field.type());
        if (field.sensitive()) {
            return true;
        }
        if (type.equals("password") || type.equals("hidden")) {
            return true;
        }
        for (String hint : properties.getSensitiveFieldHints()) {
            if (!hint.isBlank() && haystack.contains(normalize(hint))) {
                return true;
            }
        }
        return false;
    }

    private FieldMatch matchFieldValue(DesktopFormField field, Map<String, Object> profile, Map<String, Object> constraints) {
        String override = directFieldValue(field, constraints);
        if (!override.isBlank()) {
            return new FieldMatch(override, "constraints", 0.95);
        }
        String key = searchable(field);
        List<String> aliases = aliasesFor(key);
        for (String alias : aliases) {
            String value = looseValue(profile, alias);
            if (!value.isBlank()) {
                return new FieldMatch(value, "profile." + alias, 0.86);
            }
        }
        String byName = directFieldValue(field, profile);
        if (!byName.isBlank()) {
            return new FieldMatch(byName, "profile.field-key", 0.78);
        }
        return FieldMatch.empty();
    }

    private String directFieldValue(DesktopFormField field, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        ArrayList<String> candidates = new ArrayList<>();
        addIfPresent(candidates, field.id());
        addIfPresent(candidates, field.name());
        addIfPresent(candidates, field.label());
        addIfPresent(candidates, field.displayName());
        addIfPresent(candidates, "field." + field.id());
        addIfPresent(candidates, "field." + field.name());
        addIfPresent(candidates, "fields." + field.id());
        addIfPresent(candidates, "fields." + field.name());
        for (String candidate : candidates) {
            String value = looseValue(source, candidate);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private List<String> aliasesFor(String key) {
        ArrayList<String> aliases = new ArrayList<>();
        if (key.contains("email") || key.contains("mail")) {
            aliases.addAll(List.of("email", "mail", "userEmail", "contactEmail"));
        }
        if (key.contains("first") && key.contains("name")) {
            aliases.addAll(List.of("firstName", "givenName", "forename", "name.first"));
        }
        if ((key.contains("last") || key.contains("surname")) && key.contains("name")) {
            aliases.addAll(List.of("lastName", "surname", "familyName", "name.last"));
        }
        if (key.contains("full") && key.contains("name")) {
            aliases.addAll(List.of("fullName", "displayName", "name"));
        }
        if (key.equals("name") || key.endsWith("name") || key.contains("username")) {
            aliases.addAll(List.of("name", "fullName", "displayName", "username"));
        }
        if (key.contains("phone") || key.contains("mobile") || key.contains("tel")) {
            aliases.addAll(List.of("phone", "mobile", "telephone", "contactPhone"));
        }
        if (key.contains("company") || key.contains("organization") || key.contains("organisation")) {
            aliases.addAll(List.of("company", "organization", "organisation", "employer"));
        }
        if (key.contains("title") || key.contains("role") || key.contains("position")) {
            aliases.addAll(List.of("title", "jobTitle", "role", "position"));
        }
        if (key.contains("address") && (key.contains("1") || key.contains("line"))) {
            aliases.addAll(List.of("addressLine1", "address1", "streetAddress", "street"));
        } else if (key.contains("address")) {
            aliases.addAll(List.of("address", "addressLine1", "streetAddress"));
        }
        if (key.contains("city") || key.contains("locality")) {
            aliases.addAll(List.of("city", "locality", "town"));
        }
        if (key.contains("postal") || key.contains("postcode") || key.contains("zip")) {
            aliases.addAll(List.of("postalCode", "postcode", "zip", "zipCode"));
        }
        if (key.contains("country")) {
            aliases.addAll(List.of("country", "countryCode"));
        }
        if (key.contains("state") || key.contains("province") || key.contains("region")) {
            aliases.addAll(List.of("state", "province", "region"));
        }
        if (key.contains("url") || key.contains("website") || key.contains("site")) {
            aliases.addAll(List.of("url", "website", "site", "homepage"));
        }
        if (key.contains("date")) {
            aliases.addAll(List.of("date", "currentDate"));
        }
        return aliases.stream().distinct().toList();
    }

    private String looseValue(Map<String, Object> source, String wantedKey) {
        if (source == null || wantedKey == null || wantedKey.isBlank()) {
            return "";
        }
        String direct = stringValue(source.get(wantedKey));
        if (!direct.isBlank()) {
            return direct;
        }
        String normalized = normalize(wantedKey);
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (normalize(entry.getKey()).equals(normalized)) {
                return stringValue(entry.getValue());
            }
        }
        return "";
    }

    private boolean optionsAccept(DesktopFormField field, String value) {
        if (field.options().isEmpty() || value.isBlank()) {
            return true;
        }
        String normalized = normalize(value);
        return field.options().stream().map(this::normalize).anyMatch(normalized::equals);
    }

    private String writeActionFor(DesktopFormField field) {
        String type = lower(field.type());
        if (type.contains("select") || !field.options().isEmpty()) {
            return "select";
        }
        if (type.contains("checkbox") || type.contains("radio")) {
            return "check";
        }
        return "fill";
    }

    private String searchable(DesktopFormField field) {
        return normalize(String.join(
                " ",
                field.id(),
                field.label(),
                field.name(),
                field.type(),
                field.placeholder(),
                stringValue(field.metadata().get("contextPrompt"))
        ));
    }

    private String fieldId(DesktopFormField field, int index) {
        if (!field.id().isBlank()) {
            return field.id();
        }
        if (!field.name().isBlank()) {
            return field.name();
        }
        return "field-" + (index + 1);
    }

    private String titleForSurface(String id) {
        return switch (id) {
            case "active-window" -> "Active window context";
            case "screen-text" -> "Visible screen text";
            case "clipboard" -> "Clipboard bridge";
            case "browser-form" -> "Browser and form model";
            case "keyboard-mouse" -> "Keyboard and pointer executor";
            default -> id;
        };
    }

    private String safetyForSurface(String id, String access) {
        if ("clipboard".equals(id)) {
            return "Disabled by default; enable read/write separately.";
        }
        if (access != null && access.contains("write")) {
            return "Requires explicit operator approval and audit logging.";
        }
        return "Read-only context surface; raw secrets should be redacted before AI enrichment.";
    }

    private List<String> sortedKeys(Map<String, Object> map) {
        return map == null ? List.of() : map.keySet().stream().sorted(Comparator.naturalOrder()).toList();
    }

    private <T> List<T> limit(List<T> values, int limit) {
        if (limit <= 0 || values.size() <= limit) {
            return List.copyOf(values);
        }
        return List.copyOf(values.subList(0, limit));
    }

    private void addIfPresent(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String stringValue(Object value) {
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

    private String normalize(String value) {
        return lower(value).replaceAll("[^a-z0-9]+", "");
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String truncate(String value, int limit) {
        if (value == null || limit <= 0 || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, limit)) + "…";
    }

    private String safeError(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record FieldMatch(String value, String source, double confidence) {
        private FieldMatch {
            value = value == null ? "" : value.trim();
            source = source == null || source.isBlank() ? "unknown" : source.trim();
            confidence = Math.max(0.0, Math.min(1.0, confidence));
        }

        static FieldMatch empty() {
            return new FieldMatch("", "none", 0.0);
        }
    }
}
