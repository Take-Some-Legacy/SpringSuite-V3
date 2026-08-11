package com.takesome.springsuite.cloudflared;

import com.takesome.springsuite.config.SuiteConfigContributor;
import com.takesome.springsuite.config.SuiteConfigFile;
import java.util.List;

public class CloudflaredConfigContributor implements SuiteConfigContributor {
    @Override
    public List<SuiteConfigFile> configFiles() {
        return List.of(new SuiteConfigFile(
                "suite-cloudflared",
                "suite-cloudflared.yml",
                "suite-cloudflared-default.yml",
                200
        ));
    }
}
