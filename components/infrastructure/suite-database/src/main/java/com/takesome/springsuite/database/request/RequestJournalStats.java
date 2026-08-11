package com.takesome.springsuite.database.request;

import java.time.Instant;

public record RequestJournalStats(
        long total,
        long last24Hours,
        long successful,
        long redirects,
        long clientErrors,
        long serverErrors,
        double averageDurationMs,
        Instant lastRecordedAt
) {
}
