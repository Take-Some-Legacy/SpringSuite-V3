package com.takesome.springsuite.agent;

import com.takesome.springsuite.config.SuiteConfigContributor;
import com.takesome.springsuite.config.SuiteConfigFile;
import java.util.List;

public class AgentConfigContributor implements SuiteConfigContributor {
    @Override
    public List<SuiteConfigFile> configFiles() {
        return List.of(new SuiteConfigFile(
                "suite-agent",
                "suite-agent.yml",
                "suite-agent-default.yml",
                450
        ));
    }
}
