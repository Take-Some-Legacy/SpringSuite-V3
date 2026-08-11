package com.takesome.springsuite.command;

import com.takesome.springsuite.config.SuiteConfigContributor;
import com.takesome.springsuite.config.SuiteConfigFile;
import java.util.List;

public class CommandConfigContributor implements SuiteConfigContributor {
    @Override
    public List<SuiteConfigFile> configFiles() {
        return List.of(new SuiteConfigFile(
                "suite-command",
                "suite-command.yml",
                "suite-command-default.yml",
                300
        ));
    }
}
