package com.takesome.springsuite.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class OpenAiTokenProvider {
    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;
    private final OpenAiLocalCredentialStore localCredentialStore;
    private final OpenAiRuntimePaths paths;
    private final OpenAiAuditService audit;
    private final HttpClient httpClient;
    private final Object lock = new Object();
    private volatile OpenAiCredential cachedCredential = OpenAiCredential.unavailable("auto", "not initialized");

    public OpenAiTokenProvider(OpenAiProperties properties, ObjectMapper objectMapper, OpenAiLocalCredentialStore localCredentialStore, OpenAiRuntimePaths paths, OpenAiAuditService audit) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.localCredentialStore = localCredentialStore;
        this.paths = paths;
        this.audit = audit;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    public OpenAiCredentialStatus status() {
        try {
            return OpenAiCredentialStatus.from(properties, current(), cachePath().toString());
        } catch (RuntimeException ex) {
            OpenAiCredential credential = OpenAiCredential.unavailable(properties.getAuth().getMode(), safeMessage(ex));
            return OpenAiCredentialStatus.from(properties, credential, cachePath().toString());
        }
    }

    public OpenAiCredentialStatus refresh() {
        long started = System.nanoTime();
        synchronized (lock) {
            cachedCredential = OpenAiCredential.unavailable(properties.getAuth().getMode(), "refresh requested");
            audit.info("OpenAI credential refresh requested", Map.of(
                    "mode", properties.getAuth().getMode(),
                    "cachePath", cachePath().toString()
            ));
            try {
                OpenAiCredentialStatus status = OpenAiCredentialStatus.from(properties, currentLocked(true), cachePath().toString());
                audit.credential("OpenAI credential refresh completed", status, Map.of("durationMs", (System.nanoTime() - started) / 1_000_000L));
                return status;
            } catch (RuntimeException ex) {
                audit.error("OpenAI credential refresh failed", Map.of(
                        "mode", properties.getAuth().getMode(),
                        "cachePath", cachePath().toString(),
                        "durationMs", (System.nanoTime() - started) / 1_000_000L,
                        "error", safeMessage(ex)
                ));
                throw ex;
            }
        }
    }

    public String authorizationHeader() {
        OpenAiCredential credential = current();
        if (!credential.available() || credential.token().isBlank()) {
            audit.warn("OpenAI authorization header unavailable", Map.of(
                    "mode", credential.mode(),
                    "kind", credential.credentialKind(),
                    "source", credential.source(),
                    "message", credential.message()
            ));
            throw new OpenAiException(credential.message().isBlank() ? "OpenAI credentials are unavailable" : credential.message());
        }
        return "Bearer " + credential.token();
    }

    public String organizationId() {
        String explicit = firstNonBlank(properties.getAuth().getOrganizationId(), env(properties.getAuth().getOrganizationIdEnv()));
        if (!explicit.isBlank()) {
            return explicit;
        }
        OpenAiCredential credential = cachedCredential;
        if (credential.available() && credential.source().startsWith("local:")) {
            return localCredentialStore.read().map(OpenAiLinkedCredential::organizationId).orElse("");
        }
        return "";
    }

    public String projectId() {
        String explicit = firstNonBlank(properties.getAuth().getProjectId(), env(properties.getAuth().getProjectIdEnv()));
        if (!explicit.isBlank()) {
            return explicit;
        }
        OpenAiCredential credential = cachedCredential;
        if (credential.available() && credential.source().startsWith("local:")) {
            return localCredentialStore.read().map(OpenAiLinkedCredential::projectId).orElse("");
        }
        return "";
    }

    private OpenAiCredential current() {
        synchronized (lock) {
            return currentLocked(false);
        }
    }

    private OpenAiCredential currentLocked(boolean forceRefresh) {
        Instant now = Instant.now();
        if (!forceRefresh && cachedCredential.available() && !cachedCredential.refreshDue(now) && !cachedCredential.expired(now)) {
            return cachedCredential;
        }
        cachedCredential = resolveCredential(now, forceRefresh);
        return cachedCredential;
    }

    private OpenAiCredential resolveCredential(Instant now, boolean forceRefresh) {
        if (!properties.isEnabled()) {
            return OpenAiCredential.unavailable("disabled", "suite.openai.enabled=false");
        }

        return switch (properties.getAuth().getMode().toLowerCase()) {
            case "disabled", "none" -> OpenAiCredential.unavailable("disabled", "OpenAI auth disabled by suite.openai.auth.mode");
            case "api-key", "api_key" -> apiKeyCredentialWithLocalFallback();
            case "access-token", "access_token" -> accessTokenCredential();
            case "local", "linked", "browser" -> linkedApiKeyCredential();
            case "workload-identity", "workload_identity", "wif" -> workloadIdentityCredential(now, forceRefresh);
            case "auto" -> autoCredential(now, forceRefresh);
            default -> OpenAiCredential.unavailable(properties.getAuth().getMode(), "unsupported OpenAI auth mode: " + properties.getAuth().getMode());
        };
    }

    private OpenAiCredential autoCredential(Instant now, boolean forceRefresh) {
        if (workloadIdentityConfigured()) {
            OpenAiCredential workloadCredential = workloadIdentityCredential(now, forceRefresh);
            if (workloadCredential.available()) {
                return workloadCredential;
            }
        }
        OpenAiCredential accessTokenCredential = accessTokenCredential();
        if (accessTokenCredential.available()) {
            return accessTokenCredential;
        }
        OpenAiCredential apiKeyCredential = apiKeyCredential();
        if (apiKeyCredential.available()) {
            return apiKeyCredential;
        }
        OpenAiCredential linkedCredential = linkedApiKeyCredential();
        if (linkedCredential.available()) {
            return linkedCredential;
        }
        return OpenAiCredential.unavailable("auto", "no OpenAI credential source configured; set OPENAI_API_KEY, configure workload identity, or open /openai/setup to link an API key");
    }

    private OpenAiCredential apiKeyCredentialWithLocalFallback() {
        OpenAiCredential apiKeyCredential = apiKeyCredential();
        return apiKeyCredential.available() ? apiKeyCredential : linkedApiKeyCredential();
    }

    private OpenAiCredential apiKeyCredential() {
        String token = env(properties.getAuth().getApiKeyEnv());
        if (token.isBlank()) {
            return OpenAiCredential.unavailable("api-key", "environment variable " + properties.getAuth().getApiKeyEnv() + " is not set");
        }
        return new OpenAiCredential(true, token, "api-key", "api_key", "env:" + properties.getAuth().getApiKeyEnv(), fingerprint(token), Instant.now(), null, null, "", false, "using API key as bearer credential");
    }

    private OpenAiCredential linkedApiKeyCredential() {
        Optional<OpenAiLinkedCredential> linked = localCredentialStore.read();
        if (linked.isEmpty()) {
            return OpenAiCredential.unavailable("local", "no local OpenAI credential linked; open /openai/setup to bind an API key");
        }
        OpenAiLinkedCredential credential = linked.get();
        return new OpenAiCredential(
                true,
                credential.apiKey(),
                "local",
                "api_key",
                "local:" + localCredentialStore.path(),
                credential.fingerprint().isBlank() ? fingerprint(credential.apiKey()) : credential.fingerprint(),
                credential.updatedAt(),
                null,
                null,
                "",
                true,
                "using locally linked OpenAI API key"
        );
    }

    private OpenAiCredential accessTokenCredential() {
        String token = env(properties.getAuth().getAccessTokenEnv());
        if (token.isBlank()) {
            return OpenAiCredential.unavailable("access-token", "environment variable " + properties.getAuth().getAccessTokenEnv() + " is not set");
        }
        return new OpenAiCredential(true, token, "access-token", "access_token", "env:" + properties.getAuth().getAccessTokenEnv(), fingerprint(token), Instant.now(), null, null, "", false, "using static OpenAI access token");
    }

    private OpenAiCredential workloadIdentityCredential(Instant now, boolean forceRefresh) {
        OpenAiProperties.WorkloadIdentity workload = properties.getAuth().getWorkloadIdentity();
        if (!workload.isEnabled()) {
            return OpenAiCredential.unavailable("workload-identity", "workload identity is disabled");
        }
        if (!workloadIdentityConfigured()) {
            return OpenAiCredential.unavailable("workload-identity", "workload identity requires subject token, identity provider id and service account id");
        }

        OpenAiCredential cached = readTokenCache().orElse(OpenAiCredential.unavailable("workload-identity", "cache miss"));
        if (!forceRefresh && cached.available() && !cached.refreshDue(now) && !cached.expired(now)) {
            return cached;
        }

        try {
            OpenAiCredential exchanged = exchangeWorkloadToken(now);
            if (properties.getAuth().isCacheAccessTokens()) {
                writeTokenCache(exchanged);
            }
            return exchanged;
        } catch (RuntimeException ex) {
            if (cached.available() && !cached.expired(now)) {
                audit.warn("OpenAI workload identity refresh failed; using cached access token", Map.of(
                        "cachePath", cachePath().toString(),
                        "expiresAt", cached.expiresAt() == null ? "" : cached.expiresAt().toString(),
                        "error", safeMessage(ex)
                ));
                return cached.withMessage("workload_identity_cache_refresh_failed", "using cached token; refresh failed: " + safeMessage(ex));
            }
            audit.error("OpenAI workload identity refresh failed without valid cache", Map.of(
                    "cachePath", cachePath().toString(),
                    "error", safeMessage(ex)
            ));
            throw ex;
        }
    }

    private boolean workloadIdentityConfigured() {
        OpenAiProperties.WorkloadIdentity workload = properties.getAuth().getWorkloadIdentity();
        return workload.isEnabled()
                && !subjectToken().isBlank()
                && !identityProviderId().isBlank()
                && !serviceAccountId().isBlank();
    }

    private OpenAiCredential exchangeWorkloadToken(Instant now) {
        OpenAiProperties.WorkloadIdentity workload = properties.getAuth().getWorkloadIdentity();
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("grant_type", workload.getGrantType());
        payload.put("subject_token_type", workload.getSubjectTokenType());
        payload.put("subject_token", subjectToken());
        payload.put("identity_provider_id", identityProviderId());
        payload.put("service_account_id", serviceAccountId());

        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(workload.getTokenUrl()))
                    .timeout(workload.getRequestTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                audit.warn("OpenAI workload identity token exchange failed", Map.of(
                        "httpStatus", response.statusCode(),
                        "body", redact(response.body())
                ));
                throw new OpenAiException("OpenAI workload token exchange failed: HTTP " + response.statusCode() + " " + redact(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String accessToken = text(root, "access_token");
            if (accessToken.isBlank()) {
                throw new OpenAiException("OpenAI workload token exchange response does not contain access_token");
            }
            long expiresIn = Math.max(1, root.path("expires_in").asLong(3600));
            Instant issuedAt = now;
            Instant expiresAt = issuedAt.plusSeconds(expiresIn);
            Instant refreshAt = refreshAt(issuedAt, expiresAt, properties.getAuth().getTokenRefreshSkew());
            String scope = text(root, "scope");
            OpenAiCredential credential = new OpenAiCredential(true, accessToken, "workload-identity", "access_token", "workload_identity_exchange", fingerprint(accessToken), issuedAt, expiresAt, refreshAt, scope, false, "exchanged workload identity subject token");
            audit.info("OpenAI workload identity token exchanged", Map.of(
                    "fingerprint", credential.fingerprint(),
                    "expiresAt", expiresAt.toString(),
                    "refreshAt", refreshAt.toString(),
                    "scope", scope,
                    "serviceAccountConfigured", !serviceAccountId().isBlank(),
                    "identityProviderConfigured", !identityProviderId().isBlank()
            ));
            return credential;
        } catch (IOException ex) {
            throw new OpenAiException("OpenAI workload token exchange failed: " + safeMessage(ex), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenAiException("OpenAI workload token exchange interrupted", ex);
        }
    }

    private Optional<OpenAiCredential> readTokenCache() {
        if (!properties.getAuth().isCacheAccessTokens() || !Files.exists(cachePath())) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(cachePath().toFile());
            String token = text(root, "access_token");
            if (token.isBlank()) {
                return Optional.empty();
            }
            Instant issuedAt = instant(root, "issued_at");
            Instant expiresAt = instant(root, "expires_at");
            Instant refreshAt = instant(root, "refresh_at");
            String scope = text(root, "scope");
            return Optional.of(new OpenAiCredential(true, token, "workload-identity", "access_token", "cache:" + cachePath(), fingerprint(token), issuedAt, expiresAt, refreshAt, scope, true, "using cached workload identity access token"));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private void writeTokenCache(OpenAiCredential credential) {
        try {
            Files.createDirectories(cachePath().getParent());
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("access_token", credential.token());
            payload.put("token_type", "Bearer");
            payload.put("issued_at", credential.issuedAt() == null ? "" : credential.issuedAt().toString());
            payload.put("expires_at", credential.expiresAt() == null ? "" : credential.expiresAt().toString());
            payload.put("refresh_at", credential.refreshAt() == null ? "" : credential.refreshAt().toString());
            payload.put("scope", credential.scope());
            payload.put("fingerprint", credential.fingerprint());
            payload.put("source", credential.source());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cachePath().toFile(), payload);
            audit.info("OpenAI workload access token cache updated", Map.of(
                    "cachePath", cachePath().toString(),
                    "fingerprint", credential.fingerprint(),
                    "expiresAt", credential.expiresAt() == null ? "" : credential.expiresAt().toString(),
                    "refreshAt", credential.refreshAt() == null ? "" : credential.refreshAt().toString()
            ));
        } catch (IOException ex) {
            audit.error("OpenAI token cache write failed", Map.of("cachePath", cachePath().toString(), "error", safeMessage(ex)));
            throw new OpenAiException("failed to write OpenAI token cache: " + cachePath(), ex);
        }
    }

    private String subjectToken() {
        OpenAiProperties.WorkloadIdentity workload = properties.getAuth().getWorkloadIdentity();
        String fromEnv = env(workload.getSubjectTokenEnv());
        if (!fromEnv.isBlank()) {
            return fromEnv;
        }
        if (!workload.getSubjectTokenFile().isBlank()) {
            try {
                return Files.readString(paths.resolveRuntimePath(workload.getSubjectTokenFile()), StandardCharsets.UTF_8).trim();
            } catch (IOException ex) {
                return "";
            }
        }
        return "";
    }

    private String identityProviderId() {
        OpenAiProperties.WorkloadIdentity workload = properties.getAuth().getWorkloadIdentity();
        return firstNonBlank(workload.getIdentityProviderId(), env(workload.getIdentityProviderIdEnv()));
    }

    private String serviceAccountId() {
        OpenAiProperties.WorkloadIdentity workload = properties.getAuth().getWorkloadIdentity();
        return firstNonBlank(workload.getServiceAccountId(), env(workload.getServiceAccountIdEnv()));
    }

    private Path cachePath() {
        return paths.resolveRuntimePath(properties.getAuth().getTokenCacheRelativePath());
    }

    private Instant refreshAt(Instant issuedAt, Instant expiresAt, Duration requestedSkew) {
        Duration lifetime = Duration.between(issuedAt, expiresAt);
        Duration skew = requestedSkew == null || requestedSkew.isNegative() ? Duration.ZERO : requestedSkew;
        if (skew.compareTo(lifetime.dividedBy(2)) > 0) {
            skew = lifetime.dividedBy(2);
        }
        Instant refreshAt = expiresAt.minus(skew);
        return refreshAt.isBefore(issuedAt) ? issuedAt : refreshAt;
    }

    private String fingerprint(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception ex) {
            return "sha256:unavailable";
        }
    }

    private String env(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String value = System.getenv(name.trim());
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }

    private String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown error";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private String redact(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String compact = body.replaceAll("\s+", " ").trim();
        for (String sensitive : List.of("access_token", "subject_token", "api_key")) {
            compact = compact.replaceAll("(?i)\"" + sensitive + "\"\s*:\s*\"[^\"]+\"", "\"" + sensitive + "\":\"<redacted>\"");
        }
        return compact.length() > 600 ? compact.substring(0, 600) + "..." : compact;
    }
}
