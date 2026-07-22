package com.takesome.springsuite.desktop;

import com.takesome.springsuite.config.SuiteConfigContributor;
import com.takesome.springsuite.config.SuiteConfigFile;
import java.util.List;

public class DesktopHelperConfigContributor implements SuiteConfigContributor {
    @Override
    public List<SuiteConfigFile> configFiles() {
        return List.of(new SuiteConfigFile(
                "suite-desktop-helper",
                "suite-desktop-helper.yml",
                "suite-desktop-helper-default.yml",
                560
        ));
    }
}
