package com.takesome.springsuite.app;

import java.nio.file.Files;
import java.nio.file.Path;

final class RuntimeLaunchContract {
    private RuntimeLaunchContract() {
    }

    static void requireSupervisedRuntime() {
        if (allowUnsupervisedDevelopmentLaunch()) {
            return;
        }
        String supervisedValue = System.getenv().getOrDefault("SPRING_SUITE_SUPERVISED", "false");
        boolean supervised = Boolean.parseBoolean(supervisedValue) || "1".equals(supervisedValue);
        long supervisorPid = parseLong(firstNonBlank(
                System.getProperty("suite.supervisor.pid", ""),
                System.getenv("SPRING_SUITE_SUPERVISOR_PID")
        ));
        String deploymentId = firstNonBlank(
                System.getProperty("suite.deployment.id", ""),
                System.getenv("SPRING_SUITE_DEPLOYMENT_ID")
        );
        if (!supervised || supervisorPid <= 0 || deploymentId.isBlank()) {
            throw new IllegalStateException(
                    "SpringSuite production runtime must be launched by suite-runtime-controller; "
                            + "use run.bat or suite-runtime-bootstrap.exe instead of java -jar."
            );
        }
    }

    private static boolean allowUnsupervisedDevelopmentLaunch() {
        String property = System.getProperty("suite.allow.unsupervised", "false");
        String environment = System.getenv().getOrDefault("SPRING_SUITE_ALLOW_UNSUPERVISED", "false");
        if (Boolean.parseBoolean(property) || "1".equals(property)
                || Boolean.parseBoolean(environment) || "1".equals(environment)) {
            return true;
        }
        return Files.isRegularFile(Path.of("build.gradle.kts"))
                && Files.isRegularFile(Path.of("settings.gradle.kts"));
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
