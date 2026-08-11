package com.takesome.springsuite.core.process;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ManagedProcessTest {
    @Test
    void terminateReapsDescendantTree() throws Exception {
        requireWindows();
        Path pidFile = Files.createTempFile("managed-process-child", ".pid");
        Files.deleteIfExists(pidFile);
        String escaped = pidFile.toString().replace("'", "''");
        String script = "$child = Start-Process powershell.exe -ArgumentList '-NoProfile','-Command','Start-Sleep -Seconds 120' -PassThru; "
                + "Set-Content -LiteralPath '" + escaped + "' -Value $child.Id; Start-Sleep -Seconds 120";
        ManagedProcess managed = ManagedProcess.start(
                new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script),
                "test-process-tree",
                Duration.ofMillis(200),
                Duration.ofSeconds(5)
        );
        int childPid = awaitPid(pidFile);
        assertTrue(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));

        ManagedProcess.TerminationReport report = managed.terminate();

        assertTrue(report.clean(), () -> "survivors: " + report.survivingPids());
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void rootExitReapsBackgroundDescendant() throws Exception {
        requireWindows();
        Path pidFile = Files.createTempFile("managed-process-orphan", ".pid");
        Files.deleteIfExists(pidFile);
        String escaped = pidFile.toString().replace("'", "''");
        String script = "$child = Start-Process powershell.exe -ArgumentList '-NoProfile','-Command','Start-Sleep -Seconds 120' -PassThru; "
                + "Set-Content -LiteralPath '" + escaped + "' -Value $child.Id";
        ManagedProcess managed = ManagedProcess.start(
                new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script),
                "test-unexpected-exit",
                Duration.ZERO,
                Duration.ofSeconds(5)
        );
        int childPid = awaitPid(pidFile);
        assertTrue(managed.process().waitFor(10, TimeUnit.SECONDS));

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }

        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        managed.complete();
    }

    private int awaitPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(pidFile)) {
                try {
                    String value = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
                    if (!value.isBlank()) {
                        return Integer.parseInt(value);
                    }
                } catch (java.io.IOException ignored) {
                    // PowerShell may still hold the file for a few milliseconds.
                }
            }
            Thread.sleep(25L);
        }
        throw new IllegalStateException("child PID was not published");
    }

    private void requireWindows() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"));
    }
}
