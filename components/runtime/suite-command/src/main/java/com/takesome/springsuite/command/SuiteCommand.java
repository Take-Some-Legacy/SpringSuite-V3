package com.takesome.springsuite.command;

public interface SuiteCommand {
    CommandDescriptor descriptor();

    CommandExecutionResult execute(CommandInvocation invocation) throws Exception;
}
