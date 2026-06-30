package com.takesome.springsuite.workspace;

import com.takesome.springsuite.config.SuiteConfigContributor;
import com.takesome.springsuite.config.SuiteConfigFile;
import java.util.List;

public class WorkspaceConfigContributor implements SuiteConfigContributor {
    @Override
    public List<SuiteConfigFile> configFiles() {
        return List.of(new SuiteConfigFile(
                "suite-workspace",
                "suite-workspace.yml",
                "suite-workspace-default.yml",
                350
        ));
    }
}
