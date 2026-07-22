package com.takesome.springsuite.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeIdentityEndpointTest {
    @Test
    void exposesIdentityExpectedByRuntimeController() {
        String oldSupervisor = System.getProperty("suite.supervisor.pid");
        String oldDeployment = System.getProperty("suite.deployment.id");
        String oldLaunch = System.getProperty("suite.launch.dir");
        try {
            System.setProperty("suite.supervisor.pid", "19984");
            System.setProperty("suite.deployment.id", "3.2.0");
            System.setProperty("suite.launch.dir", "C:\\runtime\\SpringSuite");

            Map<String, Object> response = new RuntimeIdentityEndpoint().status();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> components = (Map<String, Object>) data.get("components");

            assertThat(response.get("ok")).isEqualTo(true);
            assertThat(data.get("status")).isEqualTo("READY");
            assertThat(components.get("pid")).isInstanceOf(Long.class);
            assertThat(components.get("supervisorPid")).isEqualTo(19984L);
            assertThat(components.get("deploymentId")).isEqualTo("3.2.0");
            assertThat(components.get("launchDirectory")).isEqualTo("C:\\runtime\\SpringSuite");
        } finally {
            restore("suite.supervisor.pid", oldSupervisor);
            restore("suite.deployment.id", oldDeployment);
            restore("suite.launch.dir", oldLaunch);
        }
    }

    private void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
