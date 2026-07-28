package com.takesome.springsuite.database.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.database.DatabaseProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "suite.database", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RequestJournalFilter extends OncePerRequestFilter {
    private static final String REQUEST_ID_HEADER = "X-SpringSuite-Request-Id";
    private static final String CORRELATION_ID_HEADER = "X-Request-Id";

    private final RequestJournalService service;
    private final DatabaseProperties properties;
    private final ObjectMapper objectMapper;

    public RequestJournalFilter(
            RequestJournalService service,
            DatabaseProperties properties,
            ObjectMapper objectMapper
    ) {
        this.service = service;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        DatabaseProperties.RequestJournal journal = properties.getRequestJournal();
        if (!properties.isEnabled() || !journal.isEnabled()) return true;
        if (isStaticResourceRequest(request)) return true;
        String path = request.getRequestURI();
        boolean included = journal.getIncludePaths().isEmpty()
                || journal.getIncludePaths().stream().anyMatch(path::startsWith);
        boolean excluded = journal.getExcludePaths().stream().anyMatch(path::startsWith);
        return !included || excluded;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return true; }

    @Override
    protected boolean shouldNotFilterErrorDispatch() { return true; }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        DatabaseProperties.RequestJournal config = properties.getRequestJournal();
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        String id = UUID.randomUUID().toString();
        String correlationId = normalizeCorrelationId(request.getHeader(CORRELATION_ID_HEADER), id);
        response.setHeader(REQUEST_ID_HEADER, id);

        int requestCaptureLimit = config.getMaxRequestBodyBytes() > 0 ? config.getMaxRequestBodyBytes() : Integer.MAX_VALUE;
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request, requestCaptureLimit);
        int responseCaptureLimit = config.isCaptureResponseBody()
                ? (config.getMaxResponseBodyBytes() > 0 ? config.getMaxResponseBodyBytes() : Integer.MAX_VALUE)
                : 0;
        CapturingHttpServletResponseWrapper responseWrapper = new CapturingHttpServletResponseWrapper(response, responseCaptureLimit);
        Throwable failure = null;
        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } catch (IOException | ServletException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            Instant completedAt = Instant.now();
            long durationMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            try {
                persist(id, correlationId, requestWrapper, responseWrapper, startedAt, completedAt, durationMs, failure, config);
            } catch (RuntimeException ignored) {
                // Persistence is observational and must never change the HTTP response path.
            }
        }
    }

    private void persist(
            String id,
            String correlationId,
            ContentCachingRequestWrapper request,
            CapturingHttpServletResponseWrapper response,
            Instant startedAt,
            Instant completedAt,
            long durationMs,
            Throwable failure,
            DatabaseProperties.RequestJournal config
    ) {
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(
                objectMapper,
                config.isRedactSensitiveData(),
                config.getMaxHeaderValueChars()
        );
        byte[] requestBytes = config.isCaptureRequestBody() ? request.getContentAsByteArray() : new byte[0];
        long declaredRequestSize = request.getContentLengthLong();
        long requestSize = declaredRequestSize >= 0 ? declaredRequestSize : requestBytes.length;
        boolean requestTruncated = config.isCaptureRequestBody()
                && config.getMaxRequestBodyBytes() > 0
                && (requestSize > requestBytes.length || requestBytes.length >= config.getMaxRequestBodyBytes());
        byte[] responseBytes = config.isCaptureResponseBody() ? response.capturedBody() : new byte[0];
        long responseSize = response.totalBytes();
        boolean responseTruncated = config.isCaptureResponseBody() && response.truncated();
        String requestBody = config.isCaptureRequestBody()
                ? sanitizer.body(requestBytes, request.getContentType(), request.getCharacterEncoding(), requestTruncated, requestSize)
                : "<request body capture disabled>";
        String responseBody = config.isCaptureResponseBody()
                ? sanitizer.body(responseBytes, response.getContentType(), response.getCharacterEncoding(), responseTruncated, responseSize)
                : "<response body capture disabled>";
        String exceptionType = failure == null ? "" : failure.getClass().getName();
        String exceptionMessage = failure == null ? "" : sanitizer.sanitizeThrowableMessage(failure.getMessage());
        String queryString = sanitizer.queryString(request.getQueryString());
        String requestHeaders = sanitizer.requestHeaders(request);
        String responseHeaders = sanitizer.responseHeaders(response);
        int responseStatus = response.getStatus();
        if (failure != null && responseStatus < 400) responseStatus = 500;
        String searchDocument = limitSearchDocument(String.join("\n",
                id,
                correlationId,
                request.getMethod(),
                request.getRequestURI(),
                queryString,
                requestHeaders,
                requestBody,
                Integer.toString(responseStatus),
                responseHeaders,
                responseBody,
                value(request.getRemoteAddr()),
                value(request.getHeader("User-Agent")),
                exceptionType,
                exceptionMessage
        ), config.getMaxSearchDocumentChars());

        service.record(new RequestJournalRecord(
                id,
                correlationId,
                startedAt,
                completedAt,
                request.getMethod().toUpperCase(Locale.ROOT),
                request.getRequestURI(),
                queryString,
                request.getScheme(),
                request.getServerName(),
                request.getServerPort(),
                value(request.getRemoteAddr()),
                value(request.getRemoteUser()),
                value(request.getHeader("User-Agent")),
                value(request.getContentType()),
                requestHeaders,
                requestBody,
                requestSize,
                requestTruncated,
                responseStatus,
                value(response.getContentType()),
                responseHeaders,
                responseBody,
                responseSize,
                responseTruncated,
                durationMs,
                exceptionType,
                exceptionMessage,
                searchDocument
        ));
    }

    private static boolean isStaticResourceRequest(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return false;
        }

        String path = request.getRequestURI();
        if (path == null || path.isBlank()) {
            return false;
        }

        if (path.equals("/") || path.equals("/index.html") || path.equals("/favicon.ico")) {
            return true;
        }

        if (path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/assets/")
                || path.startsWith("/images/")
                || path.startsWith("/fonts/")
                || path.startsWith("/webjars/")) {
            return true;
        }

        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html")
                || lower.endsWith(".css")
                || lower.endsWith(".js")
                || lower.endsWith(".mjs")
                || lower.endsWith(".map")
                || lower.endsWith(".svg")
                || lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp")
                || lower.endsWith(".ico")
                || lower.endsWith(".woff")
                || lower.endsWith(".woff2")
                || lower.endsWith(".ttf");
    }
    private static String normalizeCorrelationId(String candidate, String fallback) {
        if (candidate == null || candidate.isBlank()) return fallback;
        String trimmed = candidate.trim();
        return trimmed.length() <= 128 ? trimmed : trimmed.substring(0, 128);
    }

    private static String limitSearchDocument(String value, int maxChars) {
        return maxChars <= 0 || value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private static String value(String value) { return value == null ? "" : value; }
}
