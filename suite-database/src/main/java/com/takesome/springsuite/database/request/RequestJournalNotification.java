package com.takesome.springsuite.database.request;

import java.time.Instant;

public record RequestJournalNotification(
        String id,
        Instant startedAt,
        String method,
        String requestUri,
        String queryString,
        int responseStatus,
        long durationMs,
        String remoteAddress,
        String requestPreview,
        String exceptionType
) {
    public static RequestJournalNotification from(RequestJournalRecord record) {
        return new RequestJournalNotification(
                record.id(),
                record.startedAt(),
                record.method(),
                record.requestUri(),
                record.queryString(),
                record.responseStatus(),
                record.durationMs(),
                record.remoteAddress(),
                preview(record.requestBody()),
                record.exceptionType()
        );
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= 240 ? singleLine : singleLine.substring(0, 240) + "...";
    }
}
