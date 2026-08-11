package com.takesome.springsuite.desktop;

import com.takesome.springsuite.core.api.SuiteApiResponse;
import com.takesome.springsuite.desktop.RealInputSelfTestModels.RealInputSelfTestRequest;
import com.takesome.springsuite.desktop.RealInputSelfTestModels.RealInputSelfTestResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RealInputSelfTestController {
    private final RealInputSelfTestService selfTestService;

    public RealInputSelfTestController(RealInputSelfTestService selfTestService) {
        this.selfTestService = selfTestService;
    }

    @PostMapping("/api/desktop-helper/real-input/self-test")
    public SuiteApiResponse<RealInputSelfTestResult> selfTest(@RequestBody(required = false) RealInputSelfTestRequest request) {
        RealInputSelfTestResult result = selfTestService.selfTest(request);
        return result.ok()
                ? SuiteApiResponse.ok(result)
                : SuiteApiResponse.failed("real_input_self_test_failed", result.summary(), result);
    }
}
