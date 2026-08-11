package com.takesome.springsuite.database.request;

import java.time.Instant;

public record RequestJournalSummary(
        String id,
        String correlationId,
        Instant startedAt,
        String method,
        String requestUri,
        String queryString,
        int responseStatus,
        long durationMs,
        long requestSizeBytes,
        long responseSizeBytes,
        String remoteAddress,
        String userAgent,
        String exceptionType,
        boolean requestBodyTruncated,
        boolean responseBodyTruncated,
        String requestPreview,
        String responsePreview
) {
}
