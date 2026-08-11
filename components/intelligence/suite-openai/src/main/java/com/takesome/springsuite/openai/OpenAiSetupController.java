package com.takesome.springsuite.openai;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OpenAiSetupController {
    private final OpenAiTokenProvider tokenProvider;
    private final OpenAiLocalCredentialStore localCredentialStore;
    private final OpenAiBrowserSetupService browserSetup;
    private final OpenAiAuditService audit;

    public OpenAiSetupController(OpenAiTokenProvider tokenProvider, OpenAiLocalCredentialStore localCredentialStore, OpenAiBrowserSetupService browserSetup, OpenAiAuditService audit) {
        this.tokenProvider = tokenProvider;
        this.localCredentialStore = localCredentialStore;
        this.browserSetup = browserSetup;
        this.audit = audit;
    }

    @GetMapping(value = {"/openai/setup", "/api/openai/setup"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> setupPage(HttpServletRequest request) {
        if (!browserSetup.requestAllowed(request)) {
            if (browserSetup.enabled() && browserSetup.localOnly()) {
                String target = browserSetup.localSetupUrl();
                audit.info("OpenAI setup request redirected to loopback", Map.of(
                        "remoteAddr", request.getRemoteAddr(),
                        "host", request.getHeader("Host"),
                        "target", target
                ));
                return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                        .location(URI.create(target))
                        .contentType(MediaType.TEXT_HTML)
                        .body("");
            }
            audit.warn("OpenAI setup page blocked", Map.of("remoteAddr", request.getRemoteAddr(), "host", request.getHeader("Host")));
            return html(HttpStatus.FORBIDDEN, page("OpenAI setup blocked", "Browser setup is disabled or this request is not local.", "", request));
        }
        audit.info("OpenAI setup page served", Map.of("remoteAddr", request.getRemoteAddr(), "host", request.getHeader("Host"), "setupUrl", browserSetup.requestSetupUrl(request)));
        return html(HttpStatus.OK, page("OpenAI setup", "Link OpenAI credentials to this local SpringSuite runtime.", "", request));
    }

    @GetMapping(value = "/api/openai/link/api-key", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> apiKeySetupRedirect() {
        String target = browserSetup.localSetupUrl();
        audit.info("OpenAI API key setup endpoint redirected to loopback", Map.of("target", target));
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                .location(URI.create(target))
                .contentType(MediaType.TEXT_HTML)
                .body("");
    }

    @GetMapping("/api/openai/link/status")
    public SuiteApiResponse<OpenAiLinkResult> linkStatus(HttpServletRequest request) {
        return SuiteApiResponse.ok(linkResult(request, "OpenAI link status"));
    }

    @PostMapping("/api/openai/link/browser")
    public SuiteApiResponse<OpenAiBrowserLaunchResult> openBrowser() {
        return SuiteApiResponse.ok(browserSetup.openSetupInBrowser());
    }

    @PostMapping(value = "/api/openai/link/api-key", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SuiteApiResponse<OpenAiLinkResult> linkJson(@RequestBody OpenAiLinkRequest linkRequest, HttpServletRequest request) {
        try {
            requireBrowserSetup(request, linkRequest.setupToken());
            audit.info("OpenAI API key link requested", Map.of(
                    "via", "json",
                    "organizationConfigured", !linkRequest.organizationId().isBlank(),
                    "projectConfigured", !linkRequest.projectId().isBlank()
            ));
            localCredentialStore.saveApiKey(linkRequest.apiKey(), linkRequest.organizationId(), linkRequest.projectId());
            tokenProvider.refresh();
            return SuiteApiResponse.ok("OpenAI API key linked", linkResult(request, "OpenAI API key linked"));
        } catch (RuntimeException ex) {
            audit.warn("OpenAI API key link failed", Map.of("via", "json", "error", safeMessage(ex)));
            return SuiteApiResponse.failed("openai_link_failed", safeMessage(ex), linkResult(request, safeMessage(ex)));
        }
    }

    @PostMapping(value = "/api/openai/link/api-key", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> linkForm(@RequestParam MultiValueMap<String, String> form, HttpServletRequest request) {
        String message;
        try {
            requireBrowserSetup(request, form.getFirst("setupToken"));
            audit.info("OpenAI API key link requested", Map.of(
                    "via", "browser-form",
                    "organizationConfigured", form.getFirst("organizationId") != null && !form.getFirst("organizationId").isBlank(),
                    "projectConfigured", form.getFirst("projectId") != null && !form.getFirst("projectId").isBlank()
            ));
            localCredentialStore.saveApiKey(form.getFirst("apiKey"), form.getFirst("organizationId"), form.getFirst("projectId"));
            tokenProvider.refresh();
            message = "OpenAI API key linked.";
        } catch (RuntimeException ex) {
            message = safeMessage(ex);
            audit.warn("OpenAI API key link failed", Map.of("via", "browser-form", "error", message));
        }
        return html(HttpStatus.OK, page("OpenAI setup", message, message, request));
    }

    @PostMapping("/api/openai/link/unlink")
    public SuiteApiResponse<OpenAiLinkResult> unlink(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        try {
            String setupToken = body == null ? "" : String.valueOf(body.getOrDefault("setupToken", ""));
            requireBrowserSetup(request, setupToken);
            localCredentialStore.unlink();
            tokenProvider.refresh();
            return SuiteApiResponse.ok("OpenAI local credential removed", linkResult(request, "OpenAI local credential removed"));
        } catch (RuntimeException ex) {
            audit.warn("OpenAI local credential unlink failed", Map.of("via", "json", "error", safeMessage(ex)));
            return SuiteApiResponse.failed("openai_unlink_failed", safeMessage(ex), linkResult(request, safeMessage(ex)));
        }
    }

    @PostMapping(value = "/api/openai/link/unlink", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unlinkForm(@RequestParam MultiValueMap<String, String> form, HttpServletRequest request) {
        String message;
        try {
            requireBrowserSetup(request, form.getFirst("setupToken"));
            localCredentialStore.unlink();
            tokenProvider.refresh();
            message = "OpenAI local credential removed.";
        } catch (RuntimeException ex) {
            message = safeMessage(ex);
            audit.warn("OpenAI local credential unlink failed", Map.of("via", "browser-form", "error", message));
        }
        return html(HttpStatus.OK, page("OpenAI setup", message, message, request));
    }

    private void requireBrowserSetup(HttpServletRequest request, String setupToken) {
        if (!browserSetup.requestAllowed(request)) {
            audit.warn("OpenAI setup mutation blocked", Map.of("remoteAddr", request.getRemoteAddr(), "host", request.getHeader("Host")));
            throw new OpenAiException("OpenAI browser setup is disabled or this request is not local");
        }
        if (!browserSetup.verifySetupToken(setupToken)) {
            if (browserSetup.trustedLocalMutationRequest(request)) {
                audit.info("OpenAI setup mutation accepted from trusted same-origin loopback form", Map.of(
                        "remoteAddr", request.getRemoteAddr(),
                        "host", request.getHeader("Host")
                ));
                return;
            }
            audit.warn("OpenAI setup mutation rejected: invalid setup token", Map.of("remoteAddr", request.getRemoteAddr(), "host", request.getHeader("Host")));
            throw new OpenAiException("invalid or expired OpenAI setup token; reload the setup page");
        }
    }

    private OpenAiLinkResult linkResult(HttpServletRequest request, String message) {
        return new OpenAiLinkResult(
                tokenProvider.status(),
                localCredentialStore.status(),
                browserSetup.requestSetupUrl(request),
                message
        );
    }

    private ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
    }

    private String page(String title, String lead, String message, HttpServletRequest request) {
        String setupToken = browserSetup.setupToken();
        OpenAiCredentialStatus credential = tokenProvider.status();
        OpenAiLocalCredentialStatus linked = localCredentialStore.status();
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("OpenAI runtime", credential.available() ? "READY" : "UNAVAILABLE");
        rows.put("Credential mode", credential.mode());
        rows.put("Credential source", credential.source());
        rows.put("Credential fingerprint", credential.fingerprint());
        rows.put("Local linked", Boolean.toString(linked.linked()));
        rows.put("Local path", linked.path());
        rows.put("Organization", firstNonBlank(linked.organizationId(), "not set"));
        rows.put("Project", firstNonBlank(linked.projectId(), "not set"));
        rows.put("Message", firstNonBlank(message, credential.message(), linked.message()));

        StringBuilder table = new StringBuilder();
        for (Map.Entry<String, String> entry : rows.entrySet()) {
            table.append("<tr><th>").append(escape(entry.getKey())).append("</th><td>").append(escape(entry.getValue())).append("</td></tr>");
        }

        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <style>
                    :root { color-scheme: dark; font-family: Inter, Segoe UI, Arial, sans-serif; }
                    body { margin: 0; background: #090b10; color: #e7eaf0; }
                    main { max-width: 980px; margin: 0 auto; padding: 44px 22px; }
                    .card { border: 1px solid #242a38; background: #10141d; border-radius: 18px; padding: 24px; box-shadow: 0 18px 50px rgba(0,0,0,.34); }
                    h1 { margin: 0 0 10px; font-size: 30px; letter-spacing: -.03em; }
                    p { color: #aeb8c8; line-height: 1.55; }
                    a { color: #8db7ff; }
                    table { width: 100%%; border-collapse: collapse; margin: 22px 0; overflow: hidden; border-radius: 12px; }
                    th, td { text-align: left; border-bottom: 1px solid #242a38; padding: 11px 12px; vertical-align: top; }
                    th { width: 220px; color: #bac4d4; background: #141a25; }
                    input { width: 100%%; box-sizing: border-box; border: 1px solid #2d3545; background: #070a0f; color: #f1f4fa; border-radius: 10px; padding: 12px; margin: 6px 0 14px; }
                    label { color: #c7d0df; font-weight: 600; }
                    .actions { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
                    button, .button { border: 0; border-radius: 12px; padding: 12px 16px; background: #3b82f6; color: white; font-weight: 700; cursor: pointer; text-decoration: none; display: inline-block; }
                    .danger { background: #7f1d1d; }
                    .muted { color: #8793a7; font-size: 13px; }
                    .ok { color: #86efac; }
                    .bad { color: #fca5a5; }
                    code { background: #070a0f; padding: 2px 6px; border-radius: 6px; }
                  </style>
                </head>
                <body>
                <main>
                  <section class="card">
                    <h1>%s</h1>
                    <p>%s</p>
                    <p class="muted">This page is local-only by default. The browser is used as an operator UI; the credential is stored on the SpringSuite server side.</p>
                    <table>%s</table>
                    <div class="actions">
                      <a class="button" href="https://platform.openai.com/api-keys" target="_blank" rel="noreferrer">Open OpenAI API keys</a>
                      <a class="button" href="https://platform.openai.com/settings/organization/general" target="_blank" rel="noreferrer">Open org/project settings</a>
                    </div>
                    <h2>Link API key</h2>
                    <form method="post" action="/api/openai/link/api-key" autocomplete="off">
                      <input type="hidden" name="setupToken" value="%s">
                      <label>OpenAI API key</label>
                      <input type="password" name="apiKey" placeholder="sk-..." required>
                      <label>Organization ID <span class="muted">optional</span></label>
                      <input type="text" name="organizationId" placeholder="org_..." value="%s">
                      <label>Project ID <span class="muted">optional</span></label>
                      <input type="text" name="projectId" placeholder="proj_..." value="%s">
                      <div class="actions"><button type="submit">Save and bind</button></div>
                    </form>
                    <h2>Unlink local credential</h2>
                    <form method="post" action="/api/openai/link/unlink">
                      <input type="hidden" name="setupToken" value="%s">
                      <button class="danger" type="submit">Remove local credential</button>
                    </form>
                    <p class="muted">Reload this page to rotate the setup form token. Direct JSON API is available at <code>/api/openai/link/status</code>.</p>
                  </section>
                </main>
                </body>
                </html>
                """.formatted(
                escape(title),
                escape(title),
                escape(lead),
                table,
                escape(setupToken),
                escape(linked.organizationId()),
                escape(linked.projectId()),
                escape(setupToken)
        );
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String escape(String value) {
        return (value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown error";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
