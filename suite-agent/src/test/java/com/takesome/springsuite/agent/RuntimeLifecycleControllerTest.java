package com.takesome.springsuite.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;

class RuntimeLifecycleControllerTest {
    @AfterEach
    void clearSupervisorPid() {
        System.clearProperty("suite.supervisor.pid");
    }

    @Test
    void acceptsAuthenticatedLoopbackControllerWithMatchingPid() {
        SuiteAuthService authService = mock(SuiteAuthService.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        RuntimeLifecycleController controller = new RuntimeLifecycleController(authService, context);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(RuntimeLifecycleController.CONTROLLER_PID_HEADER, "4242");
        System.setProperty("suite.supervisor.pid", "4242");
        when(authService.authenticate(request)).thenReturn(new AuthContext(
                true,
                "bridge-token",
                "bridge",
                List.of("northstar.admin"),
                true
        ));

        var response = controller.shutdown(request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).containsEntry("accepted", true).containsEntry("code", "shutdown_scheduled");
        verify(context, timeout(2_000)).close();
    }

    @Test
    void rejectsNonLoopbackRequestsBeforeAuthentication() {
        SuiteAuthService authService = mock(SuiteAuthService.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        RuntimeLifecycleController controller = new RuntimeLifecycleController(authService, context);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.20.30.40");

        var response = controller.shutdown(request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).containsEntry("accepted", false).containsEntry("code", "loopback_required");
    }

    @Test
    void rejectsControllerPidMismatch() {
        SuiteAuthService authService = mock(SuiteAuthService.class);
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        RuntimeLifecycleController controller = new RuntimeLifecycleController(authService, context);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("::1");
        request.addHeader(RuntimeLifecycleController.CONTROLLER_PID_HEADER, "41");
        System.setProperty("suite.supervisor.pid", "42");
        when(authService.authenticate(request)).thenReturn(new AuthContext(
                true,
                "bridge-token",
                "bridge",
                List.of("northstar.admin"),
                true
        ));

        var response = controller.shutdown(request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("accepted", false).containsEntry("code", "controller_identity_mismatch");
    }
}
