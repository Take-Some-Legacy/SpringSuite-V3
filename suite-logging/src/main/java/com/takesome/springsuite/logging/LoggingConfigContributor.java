package com.takesome.springsuite.logging;

import com.takesome.springsuite.config.SuiteConfigContributor;
import com.takesome.springsuite.config.SuiteConfigFile;
import java.util.List;

public class LoggingConfigContributor implements SuiteConfigContributor {
    @Override
    public List<SuiteConfigFile> configFiles() {
        return List.of(new SuiteConfigFile(
                "suite-logging",
                "suite-logging.yml",
                "suite-logging-default.yml",
                100
        ));
    }
}
