package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import com.takesome.springsuite.desktop.DesktopBridgeModels.NormalizedDesktopSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class DesktopSnapshotCache {
    private final AtomicReference<DesktopSnapshot> latest = new AtomicReference<>();

    public DesktopSnapshot store(NormalizedDesktopSnapshot normalized, Duration ttl) {
        Instant ingestedAt = Instant.now();
        Duration safeTtl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(30) : ttl;
        DesktopSnapshot snapshot = new DesktopSnapshot(
                UUID.randomUUID().toString(),
                normalized.source(),
                normalized.capturedAt(),
                ingestedAt,
                ingestedAt.plus(safeTtl),
                false,
                normalized.context(),
                normalized.metadata()
        );
        latest.set(snapshot);
        return snapshot;
    }

    public Optional<DesktopSnapshot> latest() {
        return Optional.ofNullable(latest.get()).map(this::withFreshness);
    }

    public Optional<DesktopSnapshot> current() {
        return latest().filter(snapshot -> !snapshot.stale());
    }

    public void clear() {
        latest.set(null);
    }

    private DesktopSnapshot withFreshness(DesktopSnapshot snapshot) {
        return snapshot.withStale(Instant.now().isAfter(snapshot.expiresAt()));
    }
}
