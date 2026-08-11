package com.takesome.springsuite.database.request;

import com.takesome.springsuite.database.DatabaseProperties;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@ConditionalOnProperty(prefix = "suite.database", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RequestJournalService {
    private static final long ERROR_LOG_INTERVAL_MS = 30_000L;

    private final RequestJournalRepository repository;
    private final DatabaseProperties properties;
    private final OperatorLogService operatorLog;
    private final AtomicLong lastPersistenceErrorLog = new AtomicLong();
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public RequestJournalService(
            RequestJournalRepository repository,
            DatabaseProperties properties,
            OperatorLogService operatorLog
    ) {
        this.repository = repository;
        this.properties = properties;
        this.operatorLog = operatorLog;
    }

    public void record(RequestJournalRecord record) {
        try {
            repository.insert(record);
            broadcast(RequestJournalNotification.from(record));
        } catch (DataAccessException ex) {
            long now = System.currentTimeMillis();
            long previous = lastPersistenceErrorLog.get();
            if (now - previous >= ERROR_LOG_INTERVAL_MS && lastPersistenceErrorLog.compareAndSet(previous, now)) {
                operatorLog.append(OperatorLogLevel.ERROR, "database", "HTTP request journal write failed", Map.of(
                        "requestId", record.id(),
                        "method", record.method(),
                        "path", record.requestUri(),
                        "error", safeMessage(ex)
                ));
            }
        }
    }

    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event()
                    .name("request-journal-ready")
                    .data(Map.of("connected", true, "timestamp", Instant.now())));
        } catch (IOException | IllegalStateException ex) {
            emitters.remove(emitter);
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    public RequestJournalPage search(
            String query,
            String method,
            String path,
            String status,
            String from,
            String to,
            int page,
            Integer requestedSize
    ) {
        DatabaseProperties.RequestJournal journal = properties.getRequestJournal();
        int size = requestedSize == null ? journal.getDefaultPageSize() : requestedSize;
        if (size <= 0) {
            size = Integer.MAX_VALUE;
        }
        if (journal.getMaxPageSize() > 0) {
            size = Math.min(size, journal.getMaxPageSize());
        }
        int safePage = Math.max(0, page);
        StatusRange range = parseStatus(status);
        RequestJournalSearch search = new RequestJournalSearch(
                trim(query), trim(method), trim(path), range.from(), range.to(),
                parseInstant(from), parseInstant(to), safePage, size
        );
        return repository.search(search);
    }

    public Optional<RequestJournalRecord> findById(String id) {
        return repository.findById(id);
    }

    public RequestJournalStats stats() {
        return repository.stats(Instant.now().minusSeconds(86_400));
    }

    private void broadcast(RequestJournalNotification notification) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .id(notification.id())
                        .name("request-journal")
                        .data(notification));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(emitter);
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // The emitter is already closed.
                }
            }
        }
    }

    private StatusRange parseStatus(String status) {
        String normalized = trim(status).toLowerCase();
        if (normalized.isBlank()) return new StatusRange(null, null);
        if (normalized.matches("[1-5]xx")) {
            int group = Character.digit(normalized.charAt(0), 10);
            return new StatusRange(group * 100, group * 100 + 99);
        }
        try {
            int exact = Integer.parseInt(normalized);
            if (exact < 100 || exact > 599) {
                throw new IllegalArgumentException("status must be an HTTP status or status class such as 2xx");
            }
            return new StatusRange(exact, exact);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("status must be an HTTP status or status class such as 2xx", ex);
        }
    }

    private Instant parseInstant(String value) {
        String normalized = trim(value);
        if (normalized.isBlank()) return null;
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("timestamps must use ISO-8601 UTC format", ex);
        }
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }

    private static String safeMessage(Throwable ex) {
        String message = ex == null ? "" : ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private record StatusRange(Integer from, Integer to) { }
}
