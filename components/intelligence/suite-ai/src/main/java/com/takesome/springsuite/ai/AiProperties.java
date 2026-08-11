package com.takesome.springsuite.ai;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.ai")
public class AiProperties {
    private boolean enabled = true;
    private String defaultProvider = "openai";
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = valueOr(defaultProvider, "openai");
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providers);
    }

    static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static final class Provider {
        private boolean enabled = true;
        private String type = "openai-chat-compatible";
        private String name = "";
        private String vendor = "";
        private String baseUrl = "";
        private String chatEndpoint = "/chat/completions";
        private String apiKeyEnv = "";
        private String apiKey = "";
        private boolean requiresAuth = true;
        private String defaultModel = "";
        private Integer defaultMaxTokens;
        private Double defaultTemperature = 1.0;
        private Double defaultTopP;
        private Duration requestTimeout = Duration.ofSeconds(120);
        private List<String> capabilities = List.of();
        private Thinking thinking = new Thinking();
        private Map<String, Object> vendorOptions = new LinkedHashMap<>();
        private Probe probe = new Probe();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = valueOr(type, "openai-chat-compatible");
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name.trim();
        }

        public String getVendor() {
            return vendor;
        }

        public void setVendor(String vendor) {
            this.vendor = vendor == null ? "" : vendor.trim();
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        }

        public String getChatEndpoint() {
            return chatEndpoint;
        }

        public void setChatEndpoint(String chatEndpoint) {
            String value = valueOr(chatEndpoint, "/chat/completions");
            this.chatEndpoint = value.startsWith("/") ? value : "/" + value;
        }

        public String getApiKeyEnv() {
            return apiKeyEnv;
        }

        public void setApiKeyEnv(String apiKeyEnv) {
            this.apiKeyEnv = apiKeyEnv == null ? "" : apiKeyEnv.trim();
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey.trim();
        }

        public boolean isRequiresAuth() {
            return requiresAuth;
        }

        public void setRequiresAuth(boolean requiresAuth) {
            this.requiresAuth = requiresAuth;
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel == null ? "" : defaultModel.trim();
        }

        public Integer getDefaultMaxTokens() {
            return defaultMaxTokens;
        }

        public void setDefaultMaxTokens(Integer defaultMaxTokens) {
            this.defaultMaxTokens = defaultMaxTokens == null || defaultMaxTokens <= 0 ? null : defaultMaxTokens;
        }

        public Double getDefaultTemperature() {
            return defaultTemperature;
        }

        public void setDefaultTemperature(Double defaultTemperature) {
            this.defaultTemperature = defaultTemperature;
        }

        public Double getDefaultTopP() {
            return defaultTopP;
        }

        public void setDefaultTopP(Double defaultTopP) {
            this.defaultTopP = defaultTopP;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout == null ? Duration.ofSeconds(120) : requestTimeout;
        }

        public List<String> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(List<String> capabilities) {
            this.capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        }

        public Thinking getThinking() {
            return thinking;
        }

        public void setThinking(Thinking thinking) {
            this.thinking = thinking == null ? new Thinking() : thinking;
        }

        public Map<String, Object> getVendorOptions() {
            return vendorOptions;
        }

        public void setVendorOptions(Map<String, Object> vendorOptions) {
            this.vendorOptions = vendorOptions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(vendorOptions);
        }

        public Probe getProbe() {
            return probe;
        }

        public void setProbe(Probe probe) {
            this.probe = probe == null ? new Probe() : probe;
        }
    }

    public static final class Thinking {
        private boolean enabled = false;
        private String type = "enabled";
        private String reasoningEffort = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = valueOr(type, "enabled");
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public void setReasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
        }
    }

    public static final class Probe {
        private boolean enabled;
        private String endpoint = "/models";
        private Duration timeout = Duration.ofSeconds(3);
        private Duration cacheTtl = Duration.ofSeconds(5);
        private boolean requireDefaultModel;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            String value = valueOr(endpoint, "/models");
            this.endpoint = value.startsWith("/") ? value : "/" + value;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = positiveDuration(timeout, Duration.ofSeconds(3));
        }

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = positiveDuration(cacheTtl, Duration.ofSeconds(5));
        }

        public boolean isRequireDefaultModel() {
            return requireDefaultModel;
        }

        public void setRequireDefaultModel(boolean requireDefaultModel) {
            this.requireDefaultModel = requireDefaultModel;
        }

        private static Duration positiveDuration(Duration value, Duration fallback) {
            return value == null || value.isZero() || value.isNegative() ? fallback : value;
        }
    }
}
