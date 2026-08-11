package com.takesome.springsuite.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.auth")
public class SuiteAuthProperties {
    private boolean enabled = true;
    private boolean requireAuthForMcp = true;
    private String accessTokenEnv = "NORTHSTAR_BRIDGE_ACCESS_TOKEN";
    private String runtimeRoot = "";
    private String bridgeTokenRelativePath = "authority/bridge_access_token.txt";
    private String oauthRelativeRoot = "authority/oauth";
    private Duration codeTtl = Duration.ofSeconds(300);
    private Duration tokenTtl = Duration.ofHours(12);
    private Duration refreshTokenTtl = Duration.ofDays(30);
    private boolean allowHttpTokenBootstrap = false;
    private List<String> defaultScopes = new ArrayList<>(List.of("northstar.read", "northstar.write"));
    private List<String> supportedScopes = new ArrayList<>(List.of("northstar.read", "northstar.write", "northstar.exec", "northstar.admin"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequireAuthForMcp() {
        return requireAuthForMcp;
    }

    public void setRequireAuthForMcp(boolean requireAuthForMcp) {
        this.requireAuthForMcp = requireAuthForMcp;
    }

    public String getAccessTokenEnv() {
        return accessTokenEnv;
    }

    public void setAccessTokenEnv(String accessTokenEnv) {
        this.accessTokenEnv = accessTokenEnv == null ? "" : accessTokenEnv;
    }

    public String getRuntimeRoot() {
        return runtimeRoot;
    }

    public void setRuntimeRoot(String runtimeRoot) {
        this.runtimeRoot = runtimeRoot == null ? "" : runtimeRoot;
    }

    public String getBridgeTokenRelativePath() {
        return bridgeTokenRelativePath;
    }

    public void setBridgeTokenRelativePath(String bridgeTokenRelativePath) {
        this.bridgeTokenRelativePath = bridgeTokenRelativePath == null ? "authority/bridge_access_token.txt" : bridgeTokenRelativePath;
    }

    public String getOauthRelativeRoot() {
        return oauthRelativeRoot;
    }

    public void setOauthRelativeRoot(String oauthRelativeRoot) {
        this.oauthRelativeRoot = oauthRelativeRoot == null ? "authority/oauth" : oauthRelativeRoot;
    }

    public Duration getCodeTtl() {
        return codeTtl;
    }

    public void setCodeTtl(Duration codeTtl) {
        this.codeTtl = codeTtl == null ? Duration.ofSeconds(300) : codeTtl;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl == null ? Duration.ofHours(12) : tokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(30) : refreshTokenTtl;
    }

    public boolean isAllowHttpTokenBootstrap() {
        return allowHttpTokenBootstrap;
    }

    public void setAllowHttpTokenBootstrap(boolean allowHttpTokenBootstrap) {
        this.allowHttpTokenBootstrap = allowHttpTokenBootstrap;
    }

    public List<String> getDefaultScopes() {
        return defaultScopes;
    }

    public void setDefaultScopes(List<String> defaultScopes) {
        this.defaultScopes = defaultScopes == null ? new ArrayList<>() : new ArrayList<>(defaultScopes);
    }

    public List<String> getSupportedScopes() {
        return supportedScopes;
    }

    public void setSupportedScopes(List<String> supportedScopes) {
        this.supportedScopes = supportedScopes == null ? new ArrayList<>() : new ArrayList<>(supportedScopes);
    }
}
