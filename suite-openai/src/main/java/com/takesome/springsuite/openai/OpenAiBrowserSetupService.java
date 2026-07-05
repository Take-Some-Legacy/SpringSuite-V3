package com.takesome.springsuite.openai;

import jakarta.servlet.http.HttpServletRequest;
import java.awt.Desktop;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class OpenAiBrowserSetupService {
    private final OpenAiProperties properties;
    private final Environment environment;
    private final OpenAiAuditService audit;
    private final SecureRandom random = new SecureRandom();
    private String setupToken = "";
    private Instant setupTokenExpiresAt = Instant.EPOCH;

    public OpenAiBrowserSetupService(OpenAiProperties properties, Environment environment, OpenAiAuditService audit) {
        this.properties = properties;
        this.environment = environment;
        this.audit = audit;
    }

    public boolean enabled() {
        return properties.getBrowserSetup().isEnabled();
    }

    public synchronized String setupToken() {
        Instant now = Instant.now();
        if (setupToken.isBlank() || !now.isBefore(setupTokenExpiresAt)) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            setupToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            setupTokenExpiresAt = now.plus(properties.getBrowserSetup().getSetupTokenTtl());
            audit.debug("OpenAI browser setup token rotated", Map.of(
                    "expiresAt", setupTokenExpiresAt.toString(),
                    "ttlSeconds", properties.getBrowserSetup().getSetupTokenTtl().toSeconds()
            ));
        }
        return setupToken;
    }

    public synchronized boolean verifySetupToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return Instant.now().isBefore(setupTokenExpiresAt) && constantTimeEquals(token.trim(), setupToken);
    }

    public boolean requestAllowed(HttpServletRequest request) {
        if (!enabled()) {
            return false;
        }
        if (!properties.getBrowserSetup().isLocalOnly()) {
            return true;
        }
        String remoteAddr = request == null ? "" : nullSafe(request.getRemoteAddr()).toLowerCase(Locale.ROOT);
        String host = request == null ? "" : nullSafe(request.getHeader("Host")).toLowerCase(Locale.ROOT);
        boolean allowed = isLoopback(remoteAddr) && isLocalHost(host);
        if (!allowed) {
            audit.warn("OpenAI browser setup request blocked", Map.of(
                    "remoteAddr", remoteAddr,
                    "host", host,
                    "localOnly", properties.getBrowserSetup().isLocalOnly()
            ));
        }
        return allowed;
    }

    public String localSetupUrl() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8090"));
        return "http://localhost:" + port + properties.getBrowserSetup().getSetupPath();
    }

    public String requestSetupUrl(HttpServletRequest request) {
        if (request == null) {
            return localSetupUrl();
        }
        String proto = firstHeader(request, "X-Forwarded-Proto", request.isSecure() ? "https" : "http");
        String host = firstHeader(request, "X-Forwarded-Host", request.getHeader("Host"));
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return proto + "://" + host + properties.getBrowserSetup().getSetupPath();
    }

    public OpenAiBrowserLaunchResult openSetupInBrowser() {
        String url = localSetupUrl();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                audit.info("OpenAI setup page opened in default browser", Map.of("url", url, "method", "Desktop.browse"));
                return new OpenAiBrowserLaunchResult(true, url, "opened default browser");
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", "", url).start();
                audit.info("OpenAI setup page opened through Windows shell", Map.of("url", url, "method", "cmd.start"));
                return new OpenAiBrowserLaunchResult(true, url, "opened browser through Windows shell");
            }
            if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
                audit.info("OpenAI setup page opened through macOS open", Map.of("url", url, "method", "open"));
                return new OpenAiBrowserLaunchResult(true, url, "opened browser through macOS open");
            }
            new ProcessBuilder("xdg-open", url).start();
            audit.info("OpenAI setup page opened through xdg-open", Map.of("url", url, "method", "xdg-open"));
            return new OpenAiBrowserLaunchResult(true, url, "opened browser through xdg-open");
        } catch (Exception ex) {
            audit.warn("OpenAI setup page browser open failed", Map.of("url", url, "error", safeMessage(ex)));
            return new OpenAiBrowserLaunchResult(false, url, "could not open browser automatically: " + safeMessage(ex));
        }
    }

    private boolean isLoopback(String value) {
        return value.equals("127.0.0.1")
                || value.equals("0:0:0:0:0:0:0:1")
                || value.equals("::1")
                || value.equals("localhost")
                || value.startsWith("127.");
    }

    private boolean isLocalHost(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String host = value;
        int comma = host.indexOf(',');
        if (comma >= 0) {
            host = host.substring(0, comma).trim();
        }
        if (host.startsWith("[::1]")) {
            return true;
        }
        return host.startsWith("localhost") || host.startsWith("127.") || host.startsWith("[0:0:0:0:0:0:0:1]");
    }

    private String firstHeader(HttpServletRequest request, String header, String fallback) {
        String value = request.getHeader(header);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.split(",", 2)[0].trim();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }

    private String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown error";
        }
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
