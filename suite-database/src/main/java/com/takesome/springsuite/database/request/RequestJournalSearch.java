package com.takesome.springsuite.database.request;

import java.time.Instant;

public record RequestJournalSearch(
        String query,
        String method,
        String path,
        Integer statusFrom,
        Integer statusTo,
        Instant from,
        Instant to,
        int page,
        int size
) {
}
