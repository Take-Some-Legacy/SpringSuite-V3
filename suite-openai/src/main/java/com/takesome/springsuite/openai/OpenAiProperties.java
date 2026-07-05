package com.takesome.springsuite.openai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.openai")
public class OpenAiProperties {
    private boolean enabled = true;
    private String baseUrl = "https://api.openai.com/v1";
    private Auth auth = new Auth();
    private Responses responses = new Responses();
    private LocalCredential localCredential = new LocalCredential();
    private BrowserSetup browserSetup = new BrowserSetup();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = valueOr(baseUrl, "https://api.openai.com/v1");
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth == null ? new Auth() : auth;
    }

    public Responses getResponses() {
        return responses;
    }

    public void setResponses(Responses responses) {
        this.responses = responses == null ? new Responses() : responses;
    }

    public LocalCredential getLocalCredential() {
        return localCredential;
    }

    public void setLocalCredential(LocalCredential localCredential) {
        this.localCredential = localCredential == null ? new LocalCredential() : localCredential;
    }

    public BrowserSetup getBrowserSetup() {
        return browserSetup;
    }

    public void setBrowserSetup(BrowserSetup browserSetup) {
        this.browserSetup = browserSetup == null ? new BrowserSetup() : browserSetup;
    }

    static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static final class Auth {
        private String mode = "auto";
        private String apiKeyEnv = "OPENAI_API_KEY";
        private String accessTokenEnv = "OPENAI_ACCESS_TOKEN";
        private String organizationId = "";
        private String organizationIdEnv = "OPENAI_ORGANIZATION";
        private String projectId = "";
        private String projectIdEnv = "OPENAI_PROJECT";
        private Duration tokenRefreshSkew = Duration.ofMinutes(5);
        private boolean cacheAccessTokens = true;
        private String tokenCacheRelativePath = "authority/openai/app_access_token.json";
        private WorkloadIdentity workloadIdentity = new WorkloadIdentity();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = valueOr(mode, "auto").toLowerCase();
        }

        public String getApiKeyEnv() {
            return apiKeyEnv;
        }

        public void setApiKeyEnv(String apiKeyEnv) {
            this.apiKeyEnv = valueOr(apiKeyEnv, "OPENAI_API_KEY");
        }

        public String getAccessTokenEnv() {
            return accessTokenEnv;
        }

        public void setAccessTokenEnv(String accessTokenEnv) {
            this.accessTokenEnv = valueOr(accessTokenEnv, "OPENAI_ACCESS_TOKEN");
        }

        public String getOrganizationId() {
            return organizationId;
        }

        public void setOrganizationId(String organizationId) {
            this.organizationId = organizationId == null ? "" : organizationId.trim();
        }

        public String getOrganizationIdEnv() {
            return organizationIdEnv;
        }

        public void setOrganizationIdEnv(String organizationIdEnv) {
            this.organizationIdEnv = valueOr(organizationIdEnv, "OPENAI_ORGANIZATION");
        }

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId == null ? "" : projectId.trim();
        }

        public String getProjectIdEnv() {
            return projectIdEnv;
        }

        public void setProjectIdEnv(String projectIdEnv) {
            this.projectIdEnv = valueOr(projectIdEnv, "OPENAI_PROJECT");
        }

        public Duration getTokenRefreshSkew() {
            return tokenRefreshSkew;
        }

        public void setTokenRefreshSkew(Duration tokenRefreshSkew) {
            this.tokenRefreshSkew = tokenRefreshSkew == null ? Duration.ofMinutes(5) : tokenRefreshSkew;
        }

        public boolean isCacheAccessTokens() {
            return cacheAccessTokens;
        }

        public void setCacheAccessTokens(boolean cacheAccessTokens) {
            this.cacheAccessTokens = cacheAccessTokens;
        }

        public String getTokenCacheRelativePath() {
            return tokenCacheRelativePath;
        }

        public void setTokenCacheRelativePath(String tokenCacheRelativePath) {
            this.tokenCacheRelativePath = valueOr(tokenCacheRelativePath, "authority/openai/app_access_token.json");
        }

        public WorkloadIdentity getWorkloadIdentity() {
            return workloadIdentity;
        }

        public void setWorkloadIdentity(WorkloadIdentity workloadIdentity) {
            this.workloadIdentity = workloadIdentity == null ? new WorkloadIdentity() : workloadIdentity;
        }
    }

    public static final class WorkloadIdentity {
        private boolean enabled = true;
        private String tokenUrl = "https://auth.openai.com/oauth/token";
        private String grantType = "urn:ietf:params:oauth:grant-type:token-exchange";
        private String subjectTokenType = "urn:ietf:params:oauth:token-type:jwt";
        private String subjectTokenEnv = "OPENAI_EXTERNAL_OIDC_JWT";
        private String subjectTokenFile = "";
        private String identityProviderId = "";
        private String identityProviderIdEnv = "OPENAI_IDENTITY_PROVIDER_ID";
        private String serviceAccountId = "";
        private String serviceAccountIdEnv = "OPENAI_SERVICE_ACCOUNT_ID";
        private Duration requestTimeout = Duration.ofSeconds(20);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTokenUrl() {
            return tokenUrl;
        }

        public void setTokenUrl(String tokenUrl) {
            this.tokenUrl = valueOr(tokenUrl, "https://auth.openai.com/oauth/token");
        }

        public String getGrantType() {
            return grantType;
        }

        public void setGrantType(String grantType) {
            this.grantType = valueOr(grantType, "urn:ietf:params:oauth:grant-type:token-exchange");
        }

        public String getSubjectTokenType() {
            return subjectTokenType;
        }

        public void setSubjectTokenType(String subjectTokenType) {
            this.subjectTokenType = valueOr(subjectTokenType, "urn:ietf:params:oauth:token-type:jwt");
        }

        public String getSubjectTokenEnv() {
            return subjectTokenEnv;
        }

        public void setSubjectTokenEnv(String subjectTokenEnv) {
            this.subjectTokenEnv = valueOr(subjectTokenEnv, "OPENAI_EXTERNAL_OIDC_JWT");
        }

        public String getSubjectTokenFile() {
            return subjectTokenFile;
        }

        public void setSubjectTokenFile(String subjectTokenFile) {
            this.subjectTokenFile = subjectTokenFile == null ? "" : subjectTokenFile.trim();
        }

        public String getIdentityProviderId() {
            return identityProviderId;
        }

        public void setIdentityProviderId(String identityProviderId) {
            this.identityProviderId = identityProviderId == null ? "" : identityProviderId.trim();
        }

        public String getIdentityProviderIdEnv() {
            return identityProviderIdEnv;
        }

        public void setIdentityProviderIdEnv(String identityProviderIdEnv) {
            this.identityProviderIdEnv = valueOr(identityProviderIdEnv, "OPENAI_IDENTITY_PROVIDER_ID");
        }

        public String getServiceAccountId() {
            return serviceAccountId;
        }

        public void setServiceAccountId(String serviceAccountId) {
            this.serviceAccountId = serviceAccountId == null ? "" : serviceAccountId.trim();
        }

        public String getServiceAccountIdEnv() {
            return serviceAccountIdEnv;
        }

        public void setServiceAccountIdEnv(String serviceAccountIdEnv) {
            this.serviceAccountIdEnv = valueOr(serviceAccountIdEnv, "OPENAI_SERVICE_ACCOUNT_ID");
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(20) : requestTimeout;
        }
    }

    public static final class Responses {
        private String model = "gpt-5.5";
        private String endpoint = "/responses";
        private boolean store = false;
        private Duration requestTimeout = Duration.ofSeconds(120);
        private Integer maxOutputTokens = 4096;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = valueOr(model, "gpt-5.5");
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            String value = valueOr(endpoint, "/responses");
            this.endpoint = value.startsWith("/") ? value : "/" + value;
        }

        public boolean isStore() {
            return store;
        }

        public void setStore(boolean store) {
            this.store = store;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(120) : requestTimeout;
        }

        public Integer getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens == null || maxOutputTokens < 1 ? 4096 : maxOutputTokens;
        }
    }

    public static final class LocalCredential {
        private boolean enabled = true;
        private String relativePath = "authority/openai/local_credentials.json";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public void setRelativePath(String relativePath) {
            this.relativePath = valueOr(relativePath, "authority/openai/local_credentials.json");
        }
    }

    public static final class BrowserSetup {
        private boolean enabled = true;
        private boolean localOnly = true;
        private boolean autoOpenOnStartup = false;
        private String setupPath = "/openai/setup";
        private Duration setupTokenTtl = Duration.ofMinutes(15);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isLocalOnly() {
            return localOnly;
        }

        public void setLocalOnly(boolean localOnly) {
            this.localOnly = localOnly;
        }

        public boolean isAutoOpenOnStartup() {
            return autoOpenOnStartup;
        }

        public void setAutoOpenOnStartup(boolean autoOpenOnStartup) {
            this.autoOpenOnStartup = autoOpenOnStartup;
        }

        public String getSetupPath() {
            return setupPath;
        }

        public void setSetupPath(String setupPath) {
            String value = valueOr(setupPath, "/openai/setup");
            this.setupPath = value.startsWith("/") ? value : "/" + value;
        }

        public Duration getSetupTokenTtl() {
            return setupTokenTtl;
        }

        public void setSetupTokenTtl(Duration setupTokenTtl) {
            this.setupTokenTtl = setupTokenTtl == null ? Duration.ofMinutes(15) : setupTokenTtl;
        }
    }
}
