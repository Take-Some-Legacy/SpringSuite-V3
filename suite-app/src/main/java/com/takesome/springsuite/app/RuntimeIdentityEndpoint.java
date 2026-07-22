package com.takesome.springsuite.app;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/** Runtime identity contract exposed only through the management web endpoint mapping. */
@Component
@Endpoint(id = "runtimeidentity")
public final class RuntimeIdentityEndpoint {
    private final Instant startedAt = Instant.now();

    @ReadOperation
    public Map<String, Object> status() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("pid", ManagementFactory.getRuntimeMXBean().getPid());
        components.put("projectRoot", System.getProperty("suite.project.root", ""));
        components.put("workingDirectory", System.getProperty("suite.working.directory", ""));
        components.put("launchDirectory", System.getProperty("suite.launch.dir", ""));
        String supervisedValue = System.getenv().getOrDefault("SPRING_SUITE_SUPERVISED", "false");
        components.put("supervised", Boolean.parseBoolean(supervisedValue) || "1".equals(supervisedValue));
        components.put("supervisorPid", parseLong(System.getProperty("suite.supervisor.pid", "0")));
        components.put("deploymentId", System.getProperty("suite.deployment.id", ""));

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

    private long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "0" : value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
