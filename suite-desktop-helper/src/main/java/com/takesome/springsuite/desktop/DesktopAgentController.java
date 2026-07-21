package com.takesome.springsuite.desktop;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import com.takesome.springsuite.desktop.DesktopAgentModels.DesktopActiveFormInfo;
import com.takesome.springsuite.desktop.DesktopAgentModels.DesktopAgentStatus;
import com.takesome.springsuite.desktop.DesktopAgentSidecarRuntime.SidecarStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DesktopAgentController {
    private final DesktopAgentService desktopAgentService;
    private final DesktopAgentSidecarRuntime sidecarRuntime;

    public DesktopAgentController(
            DesktopAgentService desktopAgentService,
            DesktopAgentSidecarRuntime sidecarRuntime
    ) {
        this.desktopAgentService = desktopAgentService;
        this.sidecarRuntime = sidecarRuntime;
    }

    @GetMapping("/api/desktop-helper/agent/status")
    public SuiteApiResponse<DesktopAgentStatus> status() {
        return SuiteApiResponse.ok(desktopAgentService.status());
    }

    @GetMapping("/api/desktop-helper/agent/form")
    public SuiteApiResponse<DesktopActiveFormInfo> activeForm() {
        DesktopActiveFormInfo form = desktopAgentService.currentForm();
        return form.detected()
                ? SuiteApiResponse.ok("активная форма обнаружена", form)
                : SuiteApiResponse.failed("active_form_missing", "Активная форма с доступными для заполнения полями сейчас не обнаружена.", form);
    }

    @GetMapping("/api/desktop-helper/agent/sidecar")
    public SuiteApiResponse<SidecarStatus> sidecar() {
        return SuiteApiResponse.ok(sidecarRuntime.status());
    }

    @PostMapping("/api/desktop-helper/agent/sidecar/start")
    public SuiteApiResponse<SidecarStatus> startSidecar() {
        SidecarStatus status = sidecarRuntime.start();
        return status.healthy()
                ? SuiteApiResponse.ok("нативный desktop-agent запущен", status)
                : SuiteApiResponse.failed(status.code(), status.message(), status);
    }

    @PostMapping("/api/desktop-helper/agent/sidecar/restart")
    public SuiteApiResponse<SidecarStatus> restartSidecar() {
        SidecarStatus status = sidecarRuntime.restart();
        return status.healthy()
                ? SuiteApiResponse.ok("нативный desktop-agent перезапущен", status)
                : SuiteApiResponse.failed(status.code(), status.message(), status);
    }

    @PostMapping("/api/desktop-helper/agent/sidecar/health")
    public SuiteApiResponse<SidecarStatus> healthSidecar() {
        SidecarStatus status = sidecarRuntime.refreshHealth();
        return status.healthy()
                ? SuiteApiResponse.ok("нативный desktop-agent исправен", status)
                : SuiteApiResponse.failed(status.code(), status.message(), status);
    }

    @PostMapping("/api/desktop-helper/agent/scan")
    public SuiteApiResponse<DesktopAgentStatus> scan() {
        desktopAgentService.scanNow();
        return SuiteApiResponse.ok(desktopAgentService.status());
    }

    @PostMapping("/api/desktop-helper/agent/pause")
    public SuiteApiResponse<DesktopAgentStatus> pause() {
        desktopAgentService.pause();
        return SuiteApiResponse.ok(desktopAgentService.status());
    }

    @PostMapping("/api/desktop-helper/agent/resume")
    public SuiteApiResponse<DesktopAgentStatus> resume() {
        desktopAgentService.resume();
        return SuiteApiResponse.ok(desktopAgentService.status());
    }
}
