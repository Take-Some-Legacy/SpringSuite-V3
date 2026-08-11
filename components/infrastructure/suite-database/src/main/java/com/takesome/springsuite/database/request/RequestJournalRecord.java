package com.takesome.springsuite.database.request;

import java.time.Instant;

public record RequestJournalRecord(
        String id,
        String correlationId,
        Instant startedAt,
        Instant completedAt,
        String method,
        String requestUri,
        String queryString,
        String scheme,
        String host,
        int serverPort,
        String remoteAddress,
        String remoteUser,
        String userAgent,
        String requestContentType,
        String requestHeaders,
        String requestBody,
        long requestSizeBytes,
        boolean requestBodyTruncated,
        int responseStatus,
        String responseContentType,
        String responseHeaders,
        String responseBody,
        long responseSizeBytes,
        boolean responseBodyTruncated,
        long durationMs,
        String exceptionType,
        String exceptionMessage,
        String searchDocument
) {
}
