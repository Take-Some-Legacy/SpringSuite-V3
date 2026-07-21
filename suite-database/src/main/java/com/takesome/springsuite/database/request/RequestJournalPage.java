package com.takesome.springsuite.database.request;

import java.util.List;

public record RequestJournalPage(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<RequestJournalSummary> items
) {
    public RequestJournalPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
