package com.takesome.springsuite.command;

import java.util.concurrent.Callable;

public final class CommandExecutionContext {
    public enum Source {
        CONSOLE,
        API,
        INTERNAL
    }

    private static final ThreadLocal<Source> SOURCE = ThreadLocal.withInitial(() -> Source.INTERNAL);

    private CommandExecutionContext() {
    }

    public static Source source() {
        return SOURCE.get();
    }

    public static boolean isConsole() {
        return source() == Source.CONSOLE;
    }

    public static boolean isApi() {
        return source() == Source.API;
    }

    public static <T> T runAs(Source source, Callable<T> action) throws Exception {
        Source previous = SOURCE.get();
        SOURCE.set(source == null ? Source.INTERNAL : source);
        try {
            return action.call();
        } finally {
            SOURCE.set(previous);
        }
    }
}
