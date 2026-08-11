package com.takesome.springsuite.database;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.database")
public class DatabaseProperties {
    private boolean enabled = true;
    private final RequestJournal requestJournal = new RequestJournal();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public RequestJournal getRequestJournal() { return requestJournal; }

    public static final class RequestJournal {
        private boolean enabled = true;
        private boolean captureRequestBody = true;
        private boolean captureResponseBody = true;
        private boolean redactSensitiveData = true;
        private int maxRequestBodyBytes = 0;
        private int maxResponseBodyBytes = 0;
        private int maxHeaderValueChars = 0;
        private int maxSearchDocumentChars = 0;
        private int defaultPageSize = 0;
        private int maxPageSize = 0;
        private List<String> includePaths = new ArrayList<>(List.of("/"));
        private List<String> excludePaths = new ArrayList<>(List.of(
                "/api/admin/requests",
                "/api/operator/logs/stream",
                "/api/desktop-helper/browser-dom/snapshot",
                "/actuator/"
        ));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isCaptureRequestBody() { return captureRequestBody; }
        public void setCaptureRequestBody(boolean captureRequestBody) { this.captureRequestBody = captureRequestBody; }
        public boolean isCaptureResponseBody() { return captureResponseBody; }
        public void setCaptureResponseBody(boolean captureResponseBody) { this.captureResponseBody = captureResponseBody; }
        public boolean isRedactSensitiveData() { return redactSensitiveData; }
        public void setRedactSensitiveData(boolean redactSensitiveData) { this.redactSensitiveData = redactSensitiveData; }
        public int getMaxRequestBodyBytes() { return maxRequestBodyBytes; }
        public void setMaxRequestBodyBytes(int value) { this.maxRequestBodyBytes = Math.max(0, value); }
        public int getMaxResponseBodyBytes() { return maxResponseBodyBytes; }
        public void setMaxResponseBodyBytes(int value) { this.maxResponseBodyBytes = Math.max(0, value); }
        public int getMaxHeaderValueChars() { return maxHeaderValueChars; }
        public void setMaxHeaderValueChars(int value) { this.maxHeaderValueChars = Math.max(0, value); }
        public int getMaxSearchDocumentChars() { return maxSearchDocumentChars; }
        public void setMaxSearchDocumentChars(int value) { this.maxSearchDocumentChars = Math.max(0, value); }
        public int getDefaultPageSize() { return defaultPageSize; }
        public void setDefaultPageSize(int value) { this.defaultPageSize = Math.max(0, value); }
        public int getMaxPageSize() { return maxPageSize; }
        public void setMaxPageSize(int value) { this.maxPageSize = Math.max(0, value); }
        public List<String> getIncludePaths() { return includePaths; }
        public void setIncludePaths(List<String> value) { this.includePaths = normalizePaths(value, List.of("/")); }
        public List<String> getExcludePaths() { return excludePaths; }
        public void setExcludePaths(List<String> value) { this.excludePaths = normalizePaths(value, List.of("/api/admin/requests", "/api/operator/logs/stream", "/api/desktop-helper/browser-dom/snapshot", "/actuator/")); }

        private static List<String> normalizePaths(List<String> paths, List<String> fallback) {
            if (paths == null) return new ArrayList<>(fallback);
            return paths.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        }
    }
}
