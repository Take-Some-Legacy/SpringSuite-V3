package com.takesome.springsuite.logging;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class OperatorLogService {
    private static final Logger log = LoggerFactory.getLogger("operator");

    private final OperatorLoggingProperties properties;
    private final ArrayDeque<OperatorLogEntry> entries = new ArrayDeque<>();
    private final Object entriesLock = new Object();
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public OperatorLogService(OperatorLoggingProperties properties) {
        this.properties = properties;
    }

    public OperatorLogEntry append(OperatorLogLevel level, String source, String message) {
        return append(level, source, message, Map.of());
    }

    public OperatorLogEntry append(OperatorLogLevel level, String source, String message, Map<String, Object> metadata) {
        OperatorLogEntry entry = new OperatorLogEntry(
                UUID.randomUUID().toString(),
                Instant.now(),
                level == null ? OperatorLogLevel.INFO : level,
                blankToDefault(source, "suite"),
                message == null ? "" : message,
                metadata == null ? Map.of() : Collections.unmodifiableMap(metadata)
        );

        synchronized (entriesLock) {
            entries.addLast(entry);
            while (properties.getRingBufferSize() > 0 && entries.size() > properties.getRingBufferSize()) {
                entries.removeFirst();
            }
        }

        writeSlf4j(entry);
        broadcast(entry);
        return entry;
    }

    public List<OperatorLogEntry> recent(int limit) {
        int safeLimit = limit > 0
                ? (properties.getRingBufferSize() > 0 ? Math.min(limit, properties.getRingBufferSize()) : limit)
                : (properties.getRingBufferSize() > 0 ? properties.getRingBufferSize() : Integer.MAX_VALUE);
        synchronized (entriesLock) {
            ArrayList<OperatorLogEntry> snapshot = new ArrayList<>(entries);
            int from = Math.max(0, snapshot.size() - safeLimit);
            return List.copyOf(snapshot.subList(from, snapshot.size()));
        }
    }

    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));

        for (OperatorLogEntry entry : recent(50)) {
            send(emitter, entry);
        }
        return emitter;
    }

    private void broadcast(OperatorLogEntry entry) {
        for (SseEmitter emitter : emitters) {
            send(emitter, entry);
        }
    }

    private void send(SseEmitter emitter, OperatorLogEntry entry) {
        try {
            emitter.send(SseEmitter.event()
                    .id(entry.id())
                    .name("operator-log")
                    .data(entry));
        } catch (IOException | IllegalStateException ex) {
            emitters.remove(emitter);
        }
    }

    private void writeSlf4j(OperatorLogEntry entry) {
        String metadataJson = OperatorJsonFormatter.pretty(entry.metadata());
        boolean hasMetadata = metadataJson != null && !metadataJson.isBlank();
        String line = hasMetadata ? "[{}] {} ::\n{}" : "[{}] {}";
        switch (entry.level()) {
            case TRACE -> {
                if (hasMetadata) {
                    log.trace(line, entry.source(), entry.message(), metadataJson);
                } else {
                    log.trace(line, entry.source(), entry.message());
                }
            }
            case DEBUG -> {
                if (hasMetadata) {
                    log.debug(line, entry.source(), entry.message(), metadataJson);
                } else {
                    log.debug(line, entry.source(), entry.message());
                }
            }
            case INFO -> {
                if (hasMetadata) {
                    log.info(line, entry.source(), entry.message(), metadataJson);
                } else {
                    log.info(line, entry.source(), entry.message());
                }
            }
            case WARN -> {
                if (hasMetadata) {
                    log.warn(line, entry.source(), entry.message(), metadataJson);
                } else {
                    log.warn(line, entry.source(), entry.message());
                }
            }
            case ERROR -> {
                if (hasMetadata) {
                    log.error(line, entry.source(), entry.message(), metadataJson);
                } else {
                    log.error(line, entry.source(), entry.message());
                }
            }
        }
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
