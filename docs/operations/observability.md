# SpringSuite observability

SpringSuite exposes operational telemetry through Spring Boot Actuator:

- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /actuator/metrics/springsuite.operation.duration`
- `GET /actuator/metrics/springsuite.events`
- `GET /actuator/metrics/springsuite.operation.inflight`
- `GET /actuator/metrics/springsuite.state`
- `GET /actuator/prometheus`

## Metric contract

`springsuite.operation.duration` measures bounded capability latency with tags:

- `subsystem`
- `operation`
- `outcome`
- `code`

`springsuite.events` counts bounded lifecycle outcomes. `springsuite.state` reports queue depth, cache size and in-flight flags.

Metric tags must never contain URLs, paths, prompts, field identifiers, relay identifiers, user values or exception messages. `SuiteTelemetry` normalizes and truncates all tags to prevent cardinality growth.

## Correlation tracing

Every captured or ingested desktop snapshot receives a `correlationId`. The same identifier is propagated through form-plan metadata, browser command metadata, queue logs and acknowledgement logs. Correlation identifiers belong in structured log metadata, not metric tags.

Typical investigation flow:

1. Find the failed browser acknowledgement in `logs/spring-suite.log`.
2. Read its `correlationId`.
3. Search the same identifier to locate snapshot ingest, form planning and command queue events.
4. Compare the relevant latency and outcome series in `/actuator/metrics` or `/actuator/prometheus`.

## Deterministic plan cache

Only local deterministic `planFormFill` results are cached:

- maximum size: 256 plans;
- expiry: 2 seconds after write;
- cache key: SHA-256 of the complete request DTO;
- AI and ChatGPT Plus planning bypass the cache.

Cache size and hit/miss events are observable without exposing request contents.
