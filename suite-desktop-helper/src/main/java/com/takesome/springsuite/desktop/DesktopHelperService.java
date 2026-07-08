package com.takesome.springsuite.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.takesome.springsuite.toolbelt.ToolDescriptor;
import com.takesome.springsuite.toolbelt.ToolbeltService;
import java.util.ArrayList;
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

    public DesktopHelperService(
            DesktopHelperProperties properties,
            AiService aiService,
            ToolbeltService toolbeltService,
            ObjectMapper objectMapper,
            OperatorLogService logService
    ) {
        this.properties = properties;
        this.aiService = aiService;
        this.toolbeltService = toolbeltService;
        this.objectMapper = objectMapper;
        this.logService = logService;
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
        policy.put("contextTtl", properties.getContextTtl().toString());
        policy.put("maxScreenTextChars", properties.getMaxScreenTextChars());
        policy.put("maxSuggestionCount", properties.getMaxSuggestionCount());

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
        sidecarContract.put("writeActions", "disabled by default; execute only through an approval-bearing action channel");

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

        for (int i = 0; i < form.fields().size() && hints.size() < properties.getMaxSuggestionCount(); i++) {
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
            if (field.required() && field.value().isBlank()) {
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

        if (!context.url().isBlank() && hints.size() < properties.getMaxSuggestionCount()) {
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

        String aiSuggestion = askAi("desktop hints", Map.of(
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

            if (!field.value().isBlank()) {
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

        String aiSuggestion = askAi("form fill plan review", Map.of(
                "goal", safeRequest.userGoal(),
                "locale", safeRequest.locale(),
                "context", aiSafeContext(safeRequest.context()),
                "profileKeys", sortedKeys(safeRequest.profile()),
                "constraintKeys", sortedKeys(safeRequest.constraints()),
                "plan", aiSafePlan(plans)
        ));

        boolean requiresApproval = properties.isRequireApprovalForWriteActions() && fillCount > 0;
        String summary = "Planned " + plans.size() + " field action(s): " + fillCount + " fill/select, " + reviewCount + " review, " + askCount + " ask.";
        logService.append(OperatorLogLevel.INFO, SOURCE, "desktop form fill plan generated", Map.of(
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
                        "executionEnabled", properties.isAllowAutofillExecution()
                )
        );
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
                new DesktopSafetyRule("operator-audit", "Operator audit", "Hints and fill plans are recorded without raw secret values.", "operator log")
        );
    }

    private List<DesktopWorkflow> workflows() {
        return List.of(
                new DesktopWorkflow("desktop.context.snapshot", "Capture desktop context", "A sidecar or browser extension produces DesktopFocusContext from the active window, visible text and focused form.", List.of("GET /api/desktop-helper/schema", "POST /api/desktop-helper/context/analyze"), List.of()),
                new DesktopWorkflow("desktop.form.hints", "Generate form hints", "Analyze focused fields and produce validation, safety and formatting hints.", List.of("POST /api/desktop-helper/hints"), List.of()),
                new DesktopWorkflow("desktop.form.fill-plan", "Plan form filling", "Map safe profile/constraint values to detected fields and return an operator-reviewed plan.", List.of("POST /api/desktop-helper/form-fill/plan"), List.of("write approval for execution")),
                new DesktopWorkflow("desktop.action.execute-approved", "Execute approved action", "Reserved action surface for future keyboard/mouse/clipboard execution after explicit approval.", List.of("future /api/desktop-helper/actions/execute"), List.of("explicit approval", "surface enabled", "audit log"))
        );
    }

    private List<DesktopActionContract> actionContracts() {
        return List.of(
                new DesktopActionContract("read-context", "Read desktop context", "read", "none", List.of("activeWindow", "focusedElement", "screenText"), List.of("DesktopFocusContext")),
                new DesktopActionContract("analyze-context", "Analyze desktop context", "read", "none", List.of("DesktopFocusContext"), List.of("DesktopContextAnalysis")),
                new DesktopActionContract("suggest-hints", "Suggest desktop hints", "read", "none", List.of("DesktopHintRequest"), List.of("DesktopHintResponse")),
                new DesktopActionContract("plan-form-fill", "Plan form fill", "read-plan", "none", List.of("DesktopFormFillRequest"), List.of("DesktopFormFillPlan")),
                new DesktopActionContract("execute-form-fill", "Execute form fill", "write", "required", List.of("DesktopFormFillPlan", "approvalToken"), List.of("executionResult"))
        );
    }

    private List<String> endpoints() {
        return List.of(
                "GET /api/desktop-helper/status",
                "GET /api/desktop-helper/schema",
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
            safe.put("valuePresent", !field.value().isBlank());
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
        return normalize(String.join(" ", field.id(), field.label(), field.name(), field.type(), field.placeholder()));
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
        if (values.size() <= limit) {
            return List.copyOf(values);
        }
        return List.copyOf(values.subList(0, limit));
    }

    private void addIfPresent(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
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
        if (value == null || value.length() <= limit) {
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
