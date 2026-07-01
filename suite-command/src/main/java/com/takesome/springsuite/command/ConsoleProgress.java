package com.takesome.springsuite.command;

import java.util.concurrent.Callable;
import me.tongfei.progressbar.ProgressBar;

public final class ConsoleProgress {
    private ConsoleProgress() {
    }

    public static <T> T run(String taskName, Callable<T> action) throws Exception {
        if (!CommandExecutionContext.isConsole()) {
            return action.call();
        }
        String name = taskName == null || taskName.isBlank() ? "command" : taskName.trim();
        try (ProgressBar progress = new ProgressBar(name, -1)) {
            progress.setExtraMessage("running");
            T result = action.call();
            progress.step();
            progress.setExtraMessage("done");
            return result;
        }
    }
}
