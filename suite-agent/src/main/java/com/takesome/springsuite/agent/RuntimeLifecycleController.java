package com.takesome.springsuite.agent;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RuntimeLifecycleController {
    static final String CONTROLLER_PID_HEADER = "X-SpringSuite-Controller-Pid";

    private final SuiteAuthService authService;
    private final ConfigurableApplicationContext applicationContext;
    private final AtomicBoolean shutdownScheduled = new AtomicBoolean(false);

    public RuntimeLifecycleController(SuiteAuthService authService, ConfigurableApplicationContext applicationContext) {
        this.authService = authService;
        this.applicationContext = applicationContext;
    }

    @PostMapping("/api/runtime/lifecycle/shutdown")
    public ResponseEntity<Map<String, Object>> shutdown(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            return response(HttpStatus.FORBIDDEN, false, "loopback_required");
        }
        AuthContext auth = authService.authenticate(request);
        if (!auth.authenticated() || !auth.bridgeToken() || !auth.hasScope("northstar.admin")) {
            return response(HttpStatus.UNAUTHORIZED, false, "bridge_token_required");
        }
        long expectedControllerPid = parseLong(System.getProperty("suite.supervisor.pid", "0"));
        long suppliedControllerPid = parseLong(request.getHeader(CONTROLLER_PID_HEADER));
        if (expectedControllerPid <= 0 || suppliedControllerPid != expectedControllerPid) {
            return response(HttpStatus.CONFLICT, false, "controller_identity_mismatch");
        }
        if (shutdownScheduled.compareAndSet(false, true)) {
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                applicationContext.close();
            }, "suite-runtime-controller-shutdown");
            thread.setDaemon(false);
            thread.start();
        }
        return response(HttpStatus.ACCEPTED, true, "shutdown_scheduled");
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, boolean accepted, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", accepted);
        body.put("code", code);
        body.put("pid", ProcessHandle.current().pid());
        body.put("controllerPid", parseLong(System.getProperty("suite.supervisor.pid", "0")));
        return ResponseEntity.status(status).body(body);
    }

    private boolean isLoopback(String value) {
        try {
            return value != null && InetAddress.getByName(value).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "0" : value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
