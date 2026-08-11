package com.takesome.springsuite.openai;



import com.takesome.springsuite.config.SuiteConfigContributor;

import com.takesome.springsuite.config.SuiteConfigFile;

import java.util.List;



public class OpenAiConfigContributor implements SuiteConfigContributor {

    @Override

    public List<SuiteConfigFile> configFiles() {

        return List.of(new SuiteConfigFile(

                "suite-openai",

                "suite-openai.yml",

                "suite-openai-default.yml",

                520

        ));

    }

}
