package com.takesome.springsuite.toolbelt;

import com.takesome.springsuite.config.SuiteConfigContributor;
import com.takesome.springsuite.config.SuiteConfigFile;
import java.util.List;

public class ToolbeltConfigContributor implements SuiteConfigContributor {
    @Override
    public List<SuiteConfigFile> configFiles() {
        return List.of(new SuiteConfigFile(
                "suite-toolbelt",
                "suite-toolbelt.yml",
                "suite-toolbelt-default.yml",
                400
        ));
    }
}
