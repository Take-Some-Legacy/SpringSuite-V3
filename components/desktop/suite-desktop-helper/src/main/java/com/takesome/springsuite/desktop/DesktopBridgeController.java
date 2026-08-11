package com.takesome.springsuite.desktop;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopCaptureRequest;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshotResult;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DesktopBridgeController {
    private final DesktopBridgeService desktopBridgeService;

    public DesktopBridgeController(DesktopBridgeService desktopBridgeService) {
        this.desktopBridgeService = desktopBridgeService;
    }

    @PostMapping("/api/desktop-helper/context/capture")
    public SuiteApiResponse<DesktopSnapshotResult> capture(@RequestBody(required = false) DesktopCaptureRequest request) {
        DesktopSnapshotResult result = desktopBridgeService.capture(request);
        return result.ok()
                ? SuiteApiResponse.ok(result)
                : SuiteApiResponse.failed(result.code(), result.message(), result);
    }

    @PostMapping("/api/desktop-helper/context/ingest")
    public SuiteApiResponse<DesktopSnapshotResult> ingest(@RequestBody(required = false) Map<String, Object> body) {
        DesktopSnapshotResult result = desktopBridgeService.ingest(body == null ? Map.of() : body);
        return result.ok()
                ? SuiteApiResponse.ok(result)
                : SuiteApiResponse.failed(result.code(), result.message(), result);
    }

    @GetMapping("/api/desktop-helper/context/latest")
    public SuiteApiResponse<DesktopSnapshotResult> latest() {
        DesktopSnapshotResult result = desktopBridgeService.latest();
        return result.ok()
                ? SuiteApiResponse.ok(result)
                : SuiteApiResponse.failed(result.code(), result.message(), result);
    }

    @GetMapping("/api/desktop-helper/context/current")
    public SuiteApiResponse<DesktopSnapshotResult> current() {
        DesktopSnapshotResult result = desktopBridgeService.current();
        return result.ok()
                ? SuiteApiResponse.ok(result)
                : SuiteApiResponse.failed(result.code(), result.message(), result);
    }

    @DeleteMapping("/api/desktop-helper/context/latest")
    public SuiteApiResponse<Map<String, Object>> clear() {
        desktopBridgeService.clear();
        return SuiteApiResponse.ok("desktop snapshot cache cleared", Map.of("cleared", true));
    }
}
