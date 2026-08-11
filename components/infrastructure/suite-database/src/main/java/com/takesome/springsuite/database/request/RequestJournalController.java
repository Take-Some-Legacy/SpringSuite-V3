package com.takesome.springsuite.database.request;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/admin/requests")
@ConditionalOnProperty(prefix = "suite.database", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RequestJournalController {
    private final RequestJournalService service;

    public RequestJournalController(RequestJournalService service) {
        this.service = service;
    }

    @GetMapping
    public SuiteApiResponse<RequestJournalPage> search(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String method,
            @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size
    ) {
        try {
            return SuiteApiResponse.ok(service.search(query, method, path, status, from, to, page, size));
        } catch (IllegalArgumentException ex) {
            return SuiteApiResponse.failed("invalid_request_journal_search", safeMessage(ex), null);
        } catch (DataAccessException ex) {
            return SuiteApiResponse.failed("request_journal_query_failed", safeMessage(ex), null);
        }
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return service.stream();
    }

    @GetMapping("/stats")
    public SuiteApiResponse<RequestJournalStats> stats() {
        try {
            return SuiteApiResponse.ok(service.stats());
        } catch (DataAccessException ex) {
            return SuiteApiResponse.failed("request_journal_query_failed", safeMessage(ex), null);
        }
    }

    @GetMapping("/{id}")
    public SuiteApiResponse<RequestJournalRecord> detail(@PathVariable String id) {
        try {
            return service.findById(id)
                    .map(SuiteApiResponse::ok)
                    .orElseGet(() -> SuiteApiResponse.failed("request_not_found", "No request journal entry exists for id " + id, null));
        } catch (DataAccessException ex) {
            return SuiteApiResponse.failed("request_journal_query_failed", safeMessage(ex), null);
        }
    }

    private static String safeMessage(Throwable ex) {
        String message = ex == null ? "" : ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
