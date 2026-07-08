package com.takesome.springsuite.ai;

import com.takesome.springsuite.config.SuiteConfigContributor;
import com.takesome.springsuite.config.SuiteConfigFile;
import java.util.List;

public class AiConfigContributor implements SuiteConfigContributor {
    @Override
    public List<SuiteConfigFile> configFiles() {
        return List.of(new SuiteConfigFile(
                "suite-ai",
                "suite-ai.yml",
                "suite-ai-default.yml",
                500
        ));
    }
}
