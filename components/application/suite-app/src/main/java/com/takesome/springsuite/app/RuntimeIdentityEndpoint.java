package com.takesome.springsuite.app;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/** Runtime identity and lightweight live telemetry exposed through management. */
@Component
@Endpoint(id = "runtimeidentity")
public final class RuntimeIdentityEndpoint {
    private final Instant startedAt = Instant.now();

    @ReadOperation
    public Map<String, Object> status() {
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("pid", ManagementFactory.getRuntimeMXBean().getPid());
        components.put("projectRoot", System.getProperty("suite.project.root", ""));
        components.put("workingDirectory", System.getProperty("suite.working.directory", ""));
        components.put("launchDirectory", System.getProperty("suite.launch.dir", ""));
        String supervisedValue = System.getenv().getOrDefault("SPRING_SUITE_SUPERVISED", "false");
        components.put("supervised", Boolean.parseBoolean(supervisedValue) || "1".equals(supervisedValue));
        components.put("supervisorPid", parseLong(System.getProperty("suite.supervisor.pid", "0")));
        components.put("deploymentId", System.getProperty("suite.deployment.id", ""));
        components.put("uptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime());
        components.put("heapUsedBytes", heap.getUsed());
        components.put("heapCommittedBytes", heap.getCommitted());
        components.put("heapMaxBytes", heap.getMax());
        components.put("nonHeapUsedBytes", nonHeap.getUsed());
        components.put("threadCount", threads.getThreadCount());
        components.put("peakThreadCount", threads.getPeakThreadCount());
        components.put("availableProcessors", runtime.availableProcessors());
        components.put("systemLoadAverage", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        appendOperatingSystemTelemetry(components);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("application", "spring-suite");
        data.put("status", "READY");
        data.put("startedAt", startedAt);
        data.put("components", components);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("code", "ok");
        envelope.put("message", "ok");
        envelope.put("data", data);
        envelope.put("timestamp", Instant.now());
        return envelope;
    }

    private void appendOperatingSystemTelemetry(Map<String, Object> components) {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
            components.put("processCpuLoad", normalizeLoad(extended.getProcessCpuLoad()));
            components.put("systemCpuLoad", normalizeLoad(extended.getCpuLoad()));
            components.put("totalPhysicalMemoryBytes", extended.getTotalMemorySize());
            components.put("freePhysicalMemoryBytes", extended.getFreeMemorySize());
        } else {
            components.put("processCpuLoad", -1.0d);
            components.put("systemCpuLoad", -1.0d);
        }
    }

    private double normalizeLoad(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0d) {
            return -1.0d;
        }
        return Math.min(1.0d, value);
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "0" : value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
