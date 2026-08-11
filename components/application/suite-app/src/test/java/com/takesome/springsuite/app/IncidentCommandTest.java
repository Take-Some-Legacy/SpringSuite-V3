package com.takesome.springsuite.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncidentCommandTest {
    @TempDir
    Path temporaryRoot;

    private String previousWorkingDirectory;
    private IncidentCommand command;

    @BeforeEach
    void setUp() {
        previousWorkingDirectory = System.getProperty("suite.working.directory");
        System.setProperty("suite.working.directory", temporaryRoot.toString());
        command = new IncidentCommand(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (previousWorkingDirectory == null) {
            System.clearProperty("suite.working.directory");
        } else {
            System.setProperty("suite.working.directory", previousWorkingDirectory);
        }
    }

    @Test
    void reportsWhenNoCurrentIncidentExists() {
        CommandExecutionResult result = command.execute(invocation("current"));

        assertThat(result.ok()).isTrue();
        assertThat(result.code()).isEqualTo("incident_none");
        assertThat(result.data()).containsEntry("available", false);
    }

    @Test
    void returnsCurrentIncidentPreparedForAi() throws Exception {
        Path incidents = temporaryRoot.resolve(".springsuite").resolve("incidents");
        Files.createDirectories(incidents);
        new ObjectMapper().writeValue(incidents.resolve("current.json").toFile(), Map.of(
                "schema", "spring-suite.incident.v1",
                "incidentId", "incident-1",
                "phase", "deployment-health-check",
                "severity", "error",
                "message", "new runtime was unhealthy",
                "recoveryAction", "transaction-rollback"
        ));

        CommandExecutionResult result = command.execute(invocation("current"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("available", true);
        assertThat(result.data()).containsKey("aiInstruction");
        Map<?, ?> incident = (Map<?, ?>) result.data().get("incident");
        assertThat(incident.get("incidentId")).isEqualTo("incident-1");
        assertThat(incident.get("recoveryAction")).isEqualTo("transaction-rollback");
    }

    @Test
    void listsRecentIncidentsWithoutMutatingThem() throws Exception {
        Path incidents = temporaryRoot.resolve(".springsuite").resolve("incidents");
        Files.createDirectories(incidents);
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(incidents.resolve("one.json").toFile(), Map.of(
                "incidentId", "one",
                "occurredAt", "2026-07-22T08:00:00+03:00",
                "severity", "warning",
                "phase", "runtime",
                "message", "first"
        ));
        mapper.writeValue(incidents.resolve("two.json").toFile(), Map.of(
                "incidentId", "two",
                "occurredAt", "2026-07-22T08:01:00+03:00",
                "severity", "error",
                "phase", "supervisor",
                "message", "second"
        ));

        CommandExecutionResult result = command.execute(invocation("list", "1"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("count", 1).containsEntry("limit", 1);
        assertThat((List<?>) result.data().get("incidents")).hasSize(1);
        assertThat(Files.exists(incidents.resolve("one.json"))).isTrue();
        assertThat(Files.exists(incidents.resolve("two.json"))).isTrue();
    }

    private CommandInvocation invocation(String... args) {
        return new CommandInvocation(
                "incident " + String.join(" ", args),
                "incident",
                List.of(args),
                Instant.now()
        );
    }
}
