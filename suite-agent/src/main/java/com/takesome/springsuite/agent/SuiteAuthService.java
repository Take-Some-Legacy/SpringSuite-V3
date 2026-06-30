package com.takesome.springsuite.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.agent.auth.AuthCodec;
import com.takesome.springsuite.agent.auth.AuthCrypto;
import com.takesome.springsuite.agent.auth.AuthJsonStore;
import com.takesome.springsuite.agent.auth.AuthPaths;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class SuiteAuthService {
    private final SuiteAuthProperties properties;
    private final OAuthScopeRegistry scopes;
    private final AuthPaths paths;
    private final AuthJsonStore jsonStore;
    private final AuthCodec codec;
    private final AuthCrypto crypto = new AuthCrypto();

    public SuiteAuthService(SuiteAuthProperties properties, ObjectMapper objectMapper, OAuthScopeRegistry scopes) {
        this.properties = properties;
        this.scopes = scopes;
        this.paths = new AuthPaths(properties);
        this.jsonStore = new AuthJsonStore(objectMapper);
        this.codec = new AuthCodec(objectMapper);
    }

    public AuthStatus status() {
        String token = configuredBridgeToken(false).orElse("");
        Path tokenPath = bridgeTokenPath();
        return new AuthStatus(
                properties.isEnabled(),
                "bearer_or_x_northstar_bridge_token",
                properties.isRequireAuthForMcp(),
                runtimeRoot().toString(),
                tokenPath.toString(),
                !token.isBlank() || Files.exists(tokenPath),
                token.isBlank() ? "" : crypto.fingerprint(token),
                oauthRoot().toString(),
                properties.getSupportedScopes(),
                properties.getDefaultScopes()
        );
    }

    public BridgeTokenResult ensureBridgeToken(boolean reveal) {
        Path path = bridgeTokenPath();
        boolean created = false;
        String token = configuredBridgeToken(false).orElse("");
        if (token.isBlank()) {
            token = crypto.generateToken(48);
            jsonStore.writeTextSecret(path, token + "\n");
            created = true;
        }
        return new BridgeTokenResult(path.toString(), crypto.fingerprint(token), reveal ? token : "", created, false, reveal);
    }

    public BridgeTokenResult rotateBridgeToken(boolean reveal) {
        Path path = bridgeTokenPath();
        String token = crypto.generateToken(48);
        jsonStore.writeTextSecret(path, token + "\n");
        return new BridgeTokenResult(path.toString(), crypto.fingerprint(token), reveal ? token : "", false, true, reveal);
    }

    public AuthContext authenticate(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return new AuthContext(true, "disabled-auth", "none", List.of(OAuthScopeRegistry.READ, OAuthScopeRegistry.WRITE, OAuthScopeRegistry.EXEC, OAuthScopeRegistry.ADMIN), true);
        }
        String supplied = extractRequestToken(request);
        if (supplied.isBlank()) {
            return new AuthContext(false, "", "", List.of(), false);
        }
        Optional<String> bridgeToken = configuredBridgeToken(true);
        if (bridgeToken.isPresent() && crypto.constantTimeEquals(supplied, bridgeToken.get())) {
            return new AuthContext(true, "bridge-token", "bridge", List.of(OAuthScopeRegistry.READ, OAuthScopeRegistry.WRITE, OAuthScopeRegistry.EXEC, OAuthScopeRegistry.ADMIN), true);
        }
        Optional<Map<String, Object>> metadata = oauthTokenMetadata(supplied);
        if (metadata.isPresent()) {
            return new AuthContext(
                    true,
                    String.valueOf(metadata.get().getOrDefault("client_id", "oauth-client")),
                    "oauth",
                    codec.splitScopes(String.valueOf(metadata.get().getOrDefault("scope", OAuthScopeRegistry.READ))),
                    false
            );
        }
        return new AuthContext(false, "", "", List.of(), false);
    }

    public boolean hasRequiredScopes(AuthContext auth, List<String> requiredScopes) {
        if (auth == null || !auth.authenticated()) {
            return false;
        }
        for (String required : requiredScopes == null ? List.<String>of() : requiredScopes) {
            if (!auth.hasScope(required)) {
                return false;
            }
        }
        return true;
    }

    public Map<String, Object> protectedResourceMetadata(String baseUrl, String resourcePath) {
        String base = baseUrl.replaceAll("/$", "");
        String resource = base + paths.normalizePath(resourcePath, "/mcp");
        return codec.orderedMap(
                "resource", resource,
                "title", "SpringSuite Agent Bridge",
                "short_title", "SpringSuite",
                "name", "spring-suite",
                "description", "SpringSuite / NOESIS local software-authoring bridge.",
                "service_documentation", base + "/api/help.md",
                "service_icon", base + "/favicon.ico",
                "product", "SpringSuite",
                "vendor", "Take Some",
                "runtime_layout", "spring-suite-java",
                "authorization_servers", List.of(base),
                "bearer_methods_supported", List.of("header"),
                "scopes_supported", properties.getSupportedScopes(),
                "resource_documentation", resource
        );
    }

    public Map<String, Object> authorizationServerMetadata(String baseUrl) {
        String base = baseUrl.replaceAll("/$", "");
        return codec.orderedMap(
                "issuer", base,
                "title", "SpringSuite Agent Bridge",
                "short_title", "SpringSuite",
                "name", "spring-suite",
                "description", "SpringSuite / NOESIS local OAuth authority for MCP agents.",
                "service_documentation", base + "/api/help.md",
                "service_icon", base + "/favicon.ico",
                "product", "SpringSuite",
                "vendor", "Take Some",
                "runtime_layout", "spring-suite-java",
                "authorization_endpoint", base + "/oauth/authorize",
                "token_endpoint", base + "/oauth/token",
                "registration_endpoint", base + "/oauth/register",
                "introspection_endpoint", base + "/oauth/introspect",
                "response_types_supported", List.of("code"),
                "grant_types_supported", List.of("authorization_code"),
                "code_challenge_methods_supported", List.of("S256", "plain"),
                "token_endpoint_auth_methods_supported", List.of("none"),
                "scopes_supported", properties.getSupportedScopes()
        );
    }

    public Map<String, Object> registerClient(Map<String, Object> body) {
        String clientId = "northstar-client-" + crypto.sha256(jsonStore.objectToJson(body) + crypto.generateToken(8)).substring(0, 24);
        Map<String, Object> payload = codec.orderedMap(
                "client_id", clientId,
                "client_id_issued_at", codec.nowEpoch(),
                "token_endpoint_auth_method", "none",
                "redirect_uris", body.getOrDefault("redirect_uris", List.of()),
                "client_name", body.getOrDefault("client_name", "ChatGPT MCP Client"),
                "grant_types", List.of("authorization_code"),
                "response_types", List.of("code")
        );
        jsonStore.writeJson(oauthRoot().resolve("clients").resolve(clientId + ".json"), payload);
        return payload;
    }

    public AuthorizeResult authorize(HttpServletRequest request) {
        Map<String, String> args = codec.queryParams(request.getQueryString());
        String responseType = args.getOrDefault("response_type", "");
        String clientId = args.getOrDefault("client_id", "");
        String redirectUri = args.getOrDefault("redirect_uri", "");
        String state = args.getOrDefault("state", "");
        String scope = args.getOrDefault("scope", "northstar.read northstar.write");
        String codeChallenge = args.getOrDefault("code_challenge", "");
        String codeChallengeMethod = args.getOrDefault("code_challenge_method", "plain");
        if (!"code".equals(responseType) || clientId.isBlank() || redirectUri.isBlank()) {
            return html(400, "OAuth authorize error", "Invalid OAuth authorize request.");
        }
        String code = crypto.generateToken(32);
        List<String> granted = scopes.normalizeRequested(scope, properties.getSupportedScopes(), properties.getDefaultScopes());
        jsonStore.writeJson(oauthRoot().resolve("codes").resolve(code + ".json"), codec.orderedMap(
                "client_id", clientId,
                "redirect_uri", redirectUri,
                "scope", scopes.join(granted),
                "code_challenge", codeChallenge,
                "code_challenge_method", codeChallengeMethod,
                "issued_at", codec.nowEpoch(),
                "expires_at", codec.nowEpoch() + properties.getCodeTtl().toSeconds()
        ));
        String location = codec.redirect(redirectUri, codec.orderedStringMap("code", code, "state", state));
        String body = "<!doctype html><html><head><meta charset=\"utf-8\"><title>SpringSuite OAuth authorized</title>"
                + "<script>location.href=" + jsonStore.quoteJson(location) + ";</script></head><body>"
                + "<h1>SpringSuite Agent Bridge</h1><p>NOESIS Suite connection authorized.</p>"
                + "<p>Redirecting to <code>" + codec.escapeHtml(redirectUri) + "</code>.</p>"
                + "<a href=" + jsonStore.quoteJson(location) + ">Continue</a></body></html>";
        return new AuthorizeResult(302, Map.of("Location", location, "Content-Type", "text/html; charset=utf-8", "X-SpringSuite-Title", "SpringSuite Agent Bridge"), body);
    }

    public TokenExchangeResult token(byte[] bodyBytes, String contentType) {
        Map<String, String> data = codec.parseBody(bodyBytes, contentType);
        String grantType = data.getOrDefault("grant_type", "");
        String code = data.getOrDefault("code", "");
        String redirectUri = data.getOrDefault("redirect_uri", "");
        String clientId = data.getOrDefault("client_id", "");
        String codeVerifier = data.getOrDefault("code_verifier", "");
        if (!"authorization_code".equals(grantType) || code.isBlank()) {
            return errorToken(400, "unsupported_grant_type", "authorization_code grant with code is required");
        }
        Path usedPath = oauthRoot().resolve("used_codes").resolve(code + ".json");
        if (Files.exists(usedPath)) {
            Map<String, Object> used = jsonStore.readJson(usedPath).orElse(Map.of());
            if (codec.longAt(used, "replay_until") >= codec.nowEpoch()) {
                return tokenResponse(String.valueOf(used.getOrDefault("access_token", "")), String.valueOf(used.getOrDefault("scope", OAuthScopeRegistry.READ)));
            }
            return errorToken(400, "invalid_grant", "authorization code already used");
        }
        Path codePath = oauthRoot().resolve("codes").resolve(code + ".json");
        Optional<Map<String, Object>> savedOpt = jsonStore.readJson(codePath);
        if (savedOpt.isEmpty()) {
            return errorToken(400, "invalid_grant", "authorization code not found");
        }
        try {
            Files.deleteIfExists(codePath);
        } catch (IOException ignored) {
        }
        Map<String, Object> saved = savedOpt.get();
        if (codec.longAt(saved, "expires_at") < codec.nowEpoch()) {
            return errorToken(400, "invalid_grant", "authorization code expired");
        }
        if (!redirectUri.isBlank() && !redirectUri.equals(String.valueOf(saved.getOrDefault("redirect_uri", "")))) {
            return errorToken(400, "invalid_grant", "redirect_uri mismatch");
        }
        if (!clientId.isBlank() && !clientId.equals(String.valueOf(saved.getOrDefault("client_id", "")))) {
            return errorToken(400, "invalid_client", "client_id mismatch");
        }
        String challenge = String.valueOf(saved.getOrDefault("code_challenge", ""));
        String method = String.valueOf(saved.getOrDefault("code_challenge_method", "plain"));
        if (!challenge.isBlank()) {
            String expected = "S256".equalsIgnoreCase(method) ? crypto.encodedChallenge(codeVerifier) : codeVerifier;
            if (!expected.equals(challenge)) {
                return errorToken(400, "invalid_grant", "PKCE mismatch");
            }
        }
        String scope = String.valueOf(saved.getOrDefault("scope", OAuthScopeRegistry.READ));
        String accessToken = crypto.generateToken(48);
        String tokenHash = crypto.sha256(accessToken);
        jsonStore.writeJson(oauthRoot().resolve("tokens").resolve(tokenHash + ".json"), codec.orderedMap(
                "client_id", saved.get("client_id"),
                "scope", scope,
                "issued_at", codec.nowEpoch(),
                "expires_at", codec.nowEpoch() + properties.getTokenTtl().toSeconds()
        ));
        jsonStore.writeJson(usedPath, codec.orderedMap(
                "client_id", saved.get("client_id"),
                "scope", scope,
                "access_token", accessToken,
                "issued_at", codec.nowEpoch(),
                "replay_until", codec.nowEpoch() + 30
        ));
        return tokenResponse(accessToken, scope);
    }

    public Map<String, Object> introspect(byte[] bodyBytes, String contentType) {
        Map<String, String> data = codec.parseBody(bodyBytes, contentType);
        String token = data.getOrDefault("token", "");
        Optional<Map<String, Object>> meta = oauthTokenMetadata(token);
        if (meta.isEmpty()) {
            return Map.of("active", false);
        }
        Map<String, Object> value = new LinkedHashMap<>(meta.get());
        value.put("active", true);
        return value;
    }

    public String baseUrl(HttpServletRequest request) {
        return paths.baseUrl(request);
    }

    public String unauthorizedResourceMetadataUrl(HttpServletRequest request, String resourcePath) {
        return paths.unauthorizedResourceMetadataUrl(request, resourcePath);
    }

    public Path runtimeRoot() {
        return paths.runtimeRoot();
    }

    public Path bridgeTokenPath() {
        return paths.bridgeTokenPath();
    }

    public Path oauthRoot() {
        return paths.oauthRoot();
    }

    private Optional<Map<String, Object>> oauthTokenMetadata(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return Optional.empty();
        }
        Path path = oauthRoot().resolve("tokens").resolve(crypto.sha256(accessToken) + ".json");
        Optional<Map<String, Object>> data = jsonStore.readJson(path);
        if (data.isEmpty()) {
            return Optional.empty();
        }
        if (codec.longAt(data.get(), "expires_at") < codec.nowEpoch()) {
            return Optional.empty();
        }
        return data;
    }

    private Optional<String> configuredBridgeToken(boolean createIfMissing) {
        String envName = properties.getAccessTokenEnv();
        if (!envName.isBlank()) {
            String env = System.getenv(envName);
            if (env != null && !env.isBlank()) {
                return Optional.of(env.trim());
            }
        }
        Path path = bridgeTokenPath();
        if (Files.exists(path)) {
            try {
                String value = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!value.isBlank()) {
                    return Optional.of(value);
                }
            } catch (IOException ignored) {
            }
        }
        if (createIfMissing) {
            return Optional.of(ensureBridgeToken(true).token());
        }
        return Optional.empty();
    }

    private String extractRequestToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.toLowerCase().startsWith("bearer ")) {
            return auth.substring(7).trim();
        }
        String direct = request.getHeader("X-NorthStar-Bridge-Token");
        return direct == null ? "" : direct.trim();
    }

    private TokenExchangeResult tokenResponse(String accessToken, String scope) {
        return new TokenExchangeResult(200, Map.of("Cache-Control", "no-store", "Pragma", "no-cache"), codec.orderedMap(
                "access_token", accessToken,
                "token_type", "Bearer",
                "expires_in", properties.getTokenTtl().toSeconds(),
                "scope", scope
        ));
    }

    private TokenExchangeResult errorToken(int status, String error, String description) {
        return new TokenExchangeResult(status, Map.of("Cache-Control", "no-store", "Pragma", "no-cache"), codec.orderedMap(
                "error", error,
                "error_description", description
        ));
    }

    private AuthorizeResult html(int status, String title, String message) {
        return new AuthorizeResult(status, Map.of("Content-Type", "text/html; charset=utf-8"), "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + codec.escapeHtml(title) + "</title></head><body><h1>" + codec.escapeHtml(title) + "</h1><p>" + codec.escapeHtml(message) + "</p></body></html>");
    }
}
