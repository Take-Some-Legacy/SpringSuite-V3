package com.takesome.springsuite.agent;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OAuthController {
    private final SuiteAuthService authService;
    private final SuiteMcpProperties mcpProperties;

    public OAuthController(SuiteAuthService authService, SuiteMcpProperties mcpProperties) {
        this.authService = authService;
        this.mcpProperties = mcpProperties;
    }

    @GetMapping("/api/auth/status")
    public SuiteApiResponse<AuthStatus> status() {
        return SuiteApiResponse.ok(authService.status());
    }

    @PostMapping("/api/auth/bridge-token")
    public SuiteApiResponse<BridgeTokenResult> bridgeToken(@RequestBody(required = false) Map<String, Object> request, HttpServletRequest http) {
        boolean reveal = bool(request, "reveal");
        boolean rotate = bool(request, "rotate");
        if (reveal || rotate) {
            AuthContext auth = authService.authenticate(http);
            if (!auth.bridgeToken()) {
                return SuiteApiResponse.failed("auth_required", "bridge-token reveal/rotate requires existing bridge-token authorization", null);
            }
        }
        return SuiteApiResponse.ok(rotate ? authService.rotateBridgeToken(reveal) : authService.ensureBridgeToken(reveal));
    }

    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/**", "/mcp/.well-known/oauth-protected-resource"})
    public ResponseEntity<Map<String, Object>> protectedResource(HttpServletRequest request) {
        String path = request.getRequestURI();
        String resourcePath = path.contains("oauth-protected-resource/")
                ? "/" + path.substring(path.indexOf("oauth-protected-resource/") + "oauth-protected-resource/".length())
                : mcpProperties.getEndpoint();
        return ResponseEntity.ok(authService.protectedResourceMetadata(authService.baseUrl(request), resourcePath));
    }

    @GetMapping({"/.well-known/oauth-authorization-server", "/.well-known/openid-configuration"})
    public ResponseEntity<Map<String, Object>> authorizationServer(HttpServletRequest request) {
        return ResponseEntity.ok(authService.authorizationServerMetadata(authService.baseUrl(request)));
    }

    @PostMapping("/oauth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(authService.registerClient(body == null ? Map.of() : body));
    }

    @GetMapping(value = "/oauth/authorize", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> authorize(HttpServletRequest request) {
        AuthorizeResult result = authService.authorize(request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status());
        result.headers().forEach(builder::header);
        return builder.body(result.body());
    }

    @PostMapping("/oauth/token")
    public ResponseEntity<Map<String, Object>> token(HttpServletRequest request) throws IOException {
        TokenExchangeResult result = authService.token(request.getInputStream().readAllBytes(), request.getContentType());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status());
        result.headers().forEach(builder::header);
        return builder.body(result.body());
    }

    @PostMapping("/oauth/introspect")
    public ResponseEntity<Map<String, Object>> introspect(HttpServletRequest request) throws IOException {
        return ResponseEntity.ok(authService.introspect(request.getInputStream().readAllBytes(), request.getContentType()));
    }

    private boolean bool(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }
}
