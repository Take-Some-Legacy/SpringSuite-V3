package com.takesome.springsuite.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality metrics facade shared by SpringSuite capabilities.
 *
 * Metric tags are normalized and bounded intentionally. Never pass user text,
 * URLs, field identifiers, relay identifiers, file paths or exception messages.
 */
@Component
public final class SuiteTelemetry {
    public static final String CORRELATION_ID = "correlationId";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> gauges = new ConcurrentHashMap<>();

    public SuiteTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public Operation start(String subsystem, String operation) {
        String safeSubsystem = tag(subsystem, "unknown");
        String safeOperation = tag(operation, "operation");
        String key = safeSubsystem + ":" + safeOperation;
        AtomicInteger active = inFlight.computeIfAbsent(key, ignored -> {
            AtomicInteger value = new AtomicInteger();
            Gauge.builder("springsuite.operation.inflight", value, AtomicInteger::get)
                    .description("Current SpringSuite operations in flight")
                    .tags("subsystem", safeSubsystem, "operation", safeOperation)
                    .register(registry);
            return value;
        });
        active.incrementAndGet();
        return new Operation(registry, safeSubsystem, safeOperation, active);
    }

    public void event(String subsystem, String event, String outcome) {
        Counter.builder("springsuite.events")
                .description("SpringSuite bounded lifecycle events")
                .tags(
                        "subsystem", tag(subsystem, "unknown"),
                        "event", tag(event, "event"),
                        "outcome", tag(outcome, "unknown")
                )
                .register(registry)
                .increment();
    }

    public void setGauge(String subsystem, String gauge, int value) {
        String safeSubsystem = tag(subsystem, "unknown");
        String safeGauge = tag(gauge, "value");
        String key = safeSubsystem + ":" + safeGauge;
        AtomicInteger holder = gauges.computeIfAbsent(key, ignored -> {
            AtomicInteger created = new AtomicInteger();
            Gauge.builder("springsuite.state", created, AtomicInteger::get)
                    .description("Current bounded SpringSuite capability state")
                    .tags("subsystem", safeSubsystem, "state", safeGauge)
                    .register(registry);
            return created;
        });
        holder.set(Math.max(0, value));
    }

    public void registerGauge(String subsystem, String gauge, IntSupplier supplier) {
        String safeSubsystem = tag(subsystem, "unknown");
        String safeGauge = tag(gauge, "value");
        Gauge.builder("springsuite.state", supplier, value -> Math.max(0, value.getAsInt()))
                .description("Current bounded SpringSuite capability state")
                .tags("subsystem", safeSubsystem, "state", safeGauge)
                .strongReference(true)
                .register(registry);
    }

    public String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    private static String tag(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            normalized = fallback;
        }
        normalized = normalized.replaceAll("[^a-z0-9_.-]", "_");
        return normalized.length() <= 48 ? normalized : normalized.substring(0, 48);
    }

    public static final class Operation implements AutoCloseable {
        private final MeterRegistry registry;
        private final String subsystem;
        private final String operation;
        private final AtomicInteger inFlight;
        private final long startedNanos = System.nanoTime();
        private final AtomicBoolean completed = new AtomicBoolean();

        private Operation(
                MeterRegistry registry,
                String subsystem,
                String operation,
                AtomicInteger inFlight
        ) {
            this.registry = registry;
            this.subsystem = subsystem;
            this.operation = operation;
            this.inFlight = inFlight;
        }

        public void success() {
            complete("success", "ok");
        }

        public void rejected(String code) {
            complete("rejected", tag(code, "rejected"));
        }

        public void failure(String code) {
            complete("failure", tag(code, "failed"));
        }

        private void complete(String outcome, String code) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            inFlight.decrementAndGet();
            long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
            Timer.builder("springsuite.operation.duration")
                    .description("SpringSuite capability operation latency")
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(Duration.ofMinutes(3))
                    .tags(Tags.of(
                            "subsystem", subsystem,
                            "operation", operation,
                            "outcome", outcome,
                            "code", code
                    ))
                    .register(registry)
                    .record(elapsed, TimeUnit.NANOSECONDS);
        }

        @Override
        public void close() {
            complete("unknown", "unclosed");
        }
    }
}
