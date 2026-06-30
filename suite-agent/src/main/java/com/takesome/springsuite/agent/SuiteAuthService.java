package com.takesome.springsuite.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class SuiteAuthService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private final SuiteAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final OAuthScopeRegistry scopes;

    public SuiteAuthService(SuiteAuthProperties properties, ObjectMapper objectMapper, OAuthScopeRegistry scopes) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.scopes = scopes;
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
                token.isBlank() ? "" : fingerprint(token),
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
            token = generateToken(48);
            writeTextSecret(path, token + "\n");
            created = true;
        }
        return new BridgeTokenResult(path.toString(), fingerprint(token), reveal ? token : "", created, false, reveal);
    }

    public BridgeTokenResult rotateBridgeToken(boolean reveal) {
        Path path = bridgeTokenPath();
        String token = generateToken(48);
        writeTextSecret(path, token + "\n");
        return new BridgeTokenResult(path.toString(), fingerprint(token), reveal ? token : "", false, true, reveal);
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
        if (bridgeToken.isPresent() && constantTimeEquals(supplied, bridgeToken.get())) {
            return new AuthContext(true, "bridge-token", "bridge", List.of(OAuthScopeRegistry.READ, OAuthScopeRegistry.WRITE, OAuthScopeRegistry.EXEC, OAuthScopeRegistry.ADMIN), true);
        }
        Optional<Map<String, Object>> metadata = oauthTokenMetadata(supplied);
        if (metadata.isPresent()) {
            return new AuthContext(
                    true,
                    String.valueOf(metadata.get().getOrDefault("client_id", "oauth-client")),
                    "oauth",
                    splitScopes(String.valueOf(metadata.get().getOrDefault("scope", OAuthScopeRegistry.READ))),
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
        String resource = baseUrl.replaceAll("/$", "") + normalizePath(resourcePath, "/mcp");
        return orderedMap(
                "resource", resource,
                "title", "SpringSuite Agent Bridge",
                "short_title", "SpringSuite",
                "name", "spring-suite",
                "description", "SpringSuite / NOESIS local software-authoring bridge.",
                "service_documentation", baseUrl.replaceAll("/$", "") + "/api/help.md",
                "service_icon", baseUrl.replaceAll("/$", "") + "/favicon.ico",
                "product", "SpringSuite",
                "vendor", "Take Some",
                "runtime_layout", "spring-suite-java",
                "authorization_servers", List.of(baseUrl.replaceAll("/$", "")),
                "bearer_methods_supported", List.of("header"),
                "scopes_supported", properties.getSupportedScopes(),
                "resource_documentation", resource
        );
    }

    public Map<String, Object> authorizationServerMetadata(String baseUrl) {
        String base = baseUrl.replaceAll("/$", "");
        return orderedMap(
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
        String clientId = "northstar-client-" + sha256(objectToJson(body) + generateToken(8)).substring(0, 24);
        Map<String, Object> payload = orderedMap(
                "client_id", clientId,
                "client_id_issued_at", nowEpoch(),
                "token_endpoint_auth_method", "none",
                "redirect_uris", body.getOrDefault("redirect_uris", List.of()),
                "client_name", body.getOrDefault("client_name", "ChatGPT MCP Client"),
                "grant_types", List.of("authorization_code"),
                "response_types", List.of("code")
        );
        writeJson(oauthRoot().resolve("clients").resolve(clientId + ".json"), payload);
        return payload;
    }

    public AuthorizeResult authorize(HttpServletRequest request) {
        Map<String, String> args = queryParams(request.getQueryString());
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
        String code = generateToken(32);
        List<String> granted = scopes.normalizeRequested(scope, properties.getSupportedScopes(), properties.getDefaultScopes());
        writeJson(oauthRoot().resolve("codes").resolve(code + ".json"), orderedMap(
                "client_id", clientId,
                "redirect_uri", redirectUri,
                "scope", scopes.join(granted),
                "code_challenge", codeChallenge,
                "code_challenge_method", codeChallengeMethod,
                "issued_at", nowEpoch(),
                "expires_at", nowEpoch() + properties.getCodeTtl().toSeconds()
        ));
        String location = redirect(redirectUri, orderedStringMap("code", code, "state", state));
        String body = "<!doctype html><html><head><meta charset=\"utf-8\"><title>SpringSuite OAuth authorized</title>"
                + "<script>location.href=" + quoteJson(location) + ";</script></head><body>"
                + "<h1>SpringSuite Agent Bridge</h1><p>NOESIS Suite connection authorized.</p>"
                + "<p>Redirecting to <code>" + escapeHtml(redirectUri) + "</code>.</p>"
                + "<a href=" + quoteJson(location) + ">Continue</a></body></html>";
        return new AuthorizeResult(302, Map.of("Location", location, "Content-Type", "text/html; charset=utf-8", "X-SpringSuite-Title", "SpringSuite Agent Bridge"), body);
    }

    public TokenExchangeResult token(byte[] bodyBytes, String contentType) {
        Map<String, String> data = parseBody(bodyBytes, contentType);
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
            Map<String, Object> used = readJson(usedPath).orElse(Map.of());
            if (longAt(used, "replay_until") >= nowEpoch()) {
                return tokenResponse(String.valueOf(used.getOrDefault("access_token", "")), String.valueOf(used.getOrDefault("scope", OAuthScopeRegistry.READ)));
            }
            return errorToken(400, "invalid_grant", "authorization code already used");
        }
        Path codePath = oauthRoot().resolve("codes").resolve(code + ".json");
        Optional<Map<String, Object>> savedOpt = readJson(codePath);
        if (savedOpt.isEmpty()) {
            return errorToken(400, "invalid_grant", "authorization code not found");
        }
        try {
            Files.deleteIfExists(codePath);
        } catch (IOException ignored) {
        }
        Map<String, Object> saved = savedOpt.get();
        if (longAt(saved, "expires_at") < nowEpoch()) {
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
            String expected = "S256".equalsIgnoreCase(method) ? encodedChallenge(codeVerifier) : codeVerifier;
            if (!expected.equals(challenge)) {
                return errorToken(400, "invalid_grant", "PKCE mismatch");
            }
        }
        String scope = String.valueOf(saved.getOrDefault("scope", OAuthScopeRegistry.READ));
        String accessToken = generateToken(48);
        String tokenHash = sha256(accessToken);
        writeJson(oauthRoot().resolve("tokens").resolve(tokenHash + ".json"), orderedMap(
                "client_id", saved.get("client_id"),
                "scope", scope,
                "issued_at", nowEpoch(),
                "expires_at", nowEpoch() + properties.getTokenTtl().toSeconds()
        ));
        writeJson(usedPath, orderedMap(
                "client_id", saved.get("client_id"),
                "scope", scope,
                "access_token", accessToken,
                "issued_at", nowEpoch(),
                "replay_until", nowEpoch() + 30
        ));
        return tokenResponse(accessToken, scope);
    }

    public Map<String, Object> introspect(byte[] bodyBytes, String contentType) {
        Map<String, String> data = parseBody(bodyBytes, contentType);
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
        String proto = headerFirst(request, "X-Forwarded-Proto").orElse(request.isSecure() ? "https" : "http");
        String host = headerFirst(request, "X-Forwarded-Host").orElse(request.getHeader("Host"));
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        if (host.startsWith("127.0.0.1") || host.startsWith("localhost")) {
            proto = "http";
        }
        return proto + "://" + host;
    }

    public String unauthorizedResourceMetadataUrl(HttpServletRequest request, String resourcePath) {
        return baseUrl(request).replaceAll("/$", "") + "/.well-known/oauth-protected-resource" + normalizePath(resourcePath, "/mcp");
    }

    private Optional<Map<String, Object>> oauthTokenMetadata(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return Optional.empty();
        }
        Path path = oauthRoot().resolve("tokens").resolve(sha256(accessToken) + ".json");
        Optional<Map<String, Object>> data = readJson(path);
        if (data.isEmpty()) {
            return Optional.empty();
        }
        if (longAt(data.get(), "expires_at") < nowEpoch()) {
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
        return new TokenExchangeResult(200, Map.of("Cache-Control", "no-store", "Pragma", "no-cache"), orderedMap(
                "access_token", accessToken,
                "token_type", "Bearer",
                "expires_in", properties.getTokenTtl().toSeconds(),
                "scope", scope
        ));
    }

    private TokenExchangeResult errorToken(int status, String error, String description) {
        return new TokenExchangeResult(status, Map.of("Cache-Control", "no-store", "Pragma", "no-cache"), orderedMap(
                "error", error,
                "error_description", description
        ));
    }

    private AuthorizeResult html(int status, String title, String message) {
        return new AuthorizeResult(status, Map.of("Content-Type", "text/html; charset=utf-8"), "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + escapeHtml(title) + "</title></head><body><h1>" + escapeHtml(title) + "</h1><p>" + escapeHtml(message) + "</p></body></html>");
    }

    public Path runtimeRoot() {
        if (!properties.getRuntimeRoot().isBlank()) {
            return Paths.get(properties.getRuntimeRoot()).toAbsolutePath().normalize();
        }
        for (String env : List.of("NOESIS_SUITE_RUNTIME_ROOT", "NOESIS_SUITE_ROOT", "NORTHSTAR_SUITE_RUNTIME_ROOT", "NORTHSTAR_SUITE_ROOT", "TAKESOME_SUITE_ROOT")) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) {
                return Paths.get(value).toAbsolutePath().normalize();
            }
        }
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.isBlank()) {
                return Paths.get(local).resolve("NoesisSuite").toAbsolutePath().normalize();
            }
        }
        return Paths.get(System.getProperty("user.home"), ".local", "state", "noesis-suite").toAbsolutePath().normalize();
    }

    public Path bridgeTokenPath() {
        return runtimeRoot().resolve(properties.getBridgeTokenRelativePath()).toAbsolutePath().normalize();
    }

    public Path oauthRoot() {
        return runtimeRoot().resolve(properties.getOauthRelativeRoot()).toAbsolutePath().normalize();
    }

    private void writeJson(Path path, Map<String, Object> payload) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write auth JSON: " + path, ex);
        }
    }

    private Optional<Map<String, Object>> readJson(Path path) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), MAP_TYPE));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private void writeTextSecret(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write secret: " + path, ex);
        }
    }

    private List<String> splitScopes(String scope) {
        ArrayList<String> values = new ArrayList<>();
        for (String part : scope.split("\\s+")) {
            if (!part.isBlank()) {
                values.add(part);
            }
        }
        return values;
    }

    private Map<String, String> parseBody(byte[] bodyBytes, String contentType) {
        String raw = new String(bodyBytes == null ? new byte[0] : bodyBytes, StandardCharsets.UTF_8);
        String ctype = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase();
        if (raw.isBlank()) {
            return Map.of();
        }
        if (ctype.equals("application/json") || raw.trim().startsWith("{")) {
            try {
                Map<String, Object> map = objectMapper.readValue(raw, MAP_TYPE);
                LinkedHashMap<String, String> out = new LinkedHashMap<>();
                map.forEach((key, value) -> out.put(key, String.valueOf(value)));
                return out;
            } catch (Exception ex) {
                return Map.of();
            }
        }
        return queryParams(raw);
    }

    private Map<String, String> queryParams(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = urlDecode(parts[0]);
            String value = parts.length > 1 ? urlDecode(parts[1]) : "";
            out.put(key, value);
        }
        return out;
    }

    private String redirect(String uri, Map<String, String> params) {
        URI parsed = URI.create(uri);
        String existing = parsed.getRawQuery();
        StringBuilder query = new StringBuilder(existing == null || existing.isBlank() ? "" : existing + "&");
        boolean first = query.length() == 0;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            if (!first) {
                query.append('&');
            }
            first = false;
            query.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        return URI.create(parsed.getScheme() + ":" + (parsed.getRawSchemeSpecificPart().startsWith("//") ? "" : "")
                + parsed.getRawSchemeSpecificPart().split("\\?", 2)[0]
                + (query.length() == 0 ? "" : "?" + query)).toString();
    }

    private String encodedChallenge(String verifier) {
        byte[] digest = sha256Bytes(verifier == null ? "" : verifier);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private String generateToken(int bytes) {
        byte[] data = new byte[Math.max(16, bytes)];
        new java.security.SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private String fingerprint(String token) {
        return "sha256:" + sha256(token).substring(0, 16);
    }

    private String sha256(String text) {
        return HexFormat.of().formatHex(sha256Bytes(text));
    }

    private byte[] sha256Bytes(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private boolean constantTimeEquals(String supplied, String expected) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] key = "spring-suite-auth".getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] a = mac.doFinal(supplied.getBytes(StandardCharsets.UTF_8));
            byte[] b = mac.doFinal(expected.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(a, b);
        } catch (Exception ex) {
            return supplied.equals(expected);
        }
    }

    private long nowEpoch() {
        return Instant.now().getEpochSecond();
    }

    private long longAt(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return 0;
        }
    }

    private Optional<String> headerFirst(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.split(",", 2)[0].trim());
    }

    private String normalizePath(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private String objectToJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String quoteJson(String value) {
        return objectToJson(value);
    }

    private String escapeHtml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private LinkedHashMap<String, String> orderedStringMap(String... values) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(values[i], values[i + 1]);
        }
        return map;
    }

    private LinkedHashMap<String, Object> orderedMap(Object... values) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }
}
