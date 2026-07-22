package com.takesome.springsuite.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class SuiteTelemetryTest {
    @Test
    void recordsDurationAndReturnsInflightGaugeToZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuiteTelemetry telemetry = new SuiteTelemetry(registry);

        SuiteTelemetry.Operation operation = telemetry.start("Desktop Agent", "Scan Active Form");
        operation.success();

        assertThat(registry.find("springsuite.operation.duration")
                .tags("subsystem", "desktop_agent", "operation", "scan_active_form", "outcome", "success", "code", "ok")
                .timer())
                .isNotNull()
                .extracting(timer -> timer.count())
                .isEqualTo(1L);
        assertThat(registry.find("springsuite.operation.inflight")
                .tags("subsystem", "desktop_agent", "operation", "scan_active_form")
                .gauge())
                .isNotNull()
                .extracting(gauge -> gauge.value())
                .isEqualTo(0.0);
    }

    @Test
    void normalizesAndBoundsMetricTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuiteTelemetry telemetry = new SuiteTelemetry(registry);

        telemetry.event(
                "Browser DOM / User URL Must Never Become A Tag",
                "Relay ID 1234567890 1234567890 1234567890 1234567890",
                "Success"
        );

        Meter meter = registry.find("springsuite.events").meter();
        assertThat(meter).isNotNull();
        assertThat(meter.getId().getTags())
                .allSatisfy(tag -> {
                    assertThat(tag.getValue()).doesNotContain(" ", "/");
                    assertThat(tag.getValue().length()).isLessThanOrEqualTo(48);
                });
    }
    @Test
    void keepsSupplierGaugeStronglyReachable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuiteTelemetry telemetry = new SuiteTelemetry(registry);

        telemetry.registerGauge("browser_dom", "pending_commands", () -> 7);
        System.gc();

        assertThat(registry.find("springsuite.state")
                .tags("subsystem", "browser_dom", "state", "pending_commands")
                .gauge())
                .isNotNull()
                .extracting(gauge -> gauge.value())
                .isEqualTo(7.0);
    }

}
