package com.takesome.springsuite.app;

import com.takesome.springsuite.core.mode.SuiteOperatorMode;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SuiteOperatorModeStartupLogger implements ApplicationRunner {
    private final OperatorLogService logService;

    public SuiteOperatorModeStartupLogger(OperatorLogService logService) {
        this.logService = logService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (SuiteOperatorMode.isElevated()) {
            logService.append(OperatorLogLevel.WARN, "operator-mode", "SpringSuite started with elevated operator mode", Map.of(
                    "mode", SuiteOperatorMode.name(),
                    "source", SuiteOperatorMode.source(),
                    "policy", "elevated operator policy override active"
            ));
        }
    }
}
