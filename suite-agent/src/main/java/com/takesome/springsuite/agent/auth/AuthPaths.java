package com.takesome.springsuite.agent.auth;

import com.takesome.springsuite.agent.SuiteAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

public final class AuthPaths {
    private final SuiteAuthProperties properties;

    public AuthPaths(SuiteAuthProperties properties) {
        this.properties = properties;
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

    public String normalizePath(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private Optional<String> headerFirst(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.split(",", 2)[0].trim());
    }
}
