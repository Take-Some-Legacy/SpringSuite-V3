package com.takesome.springsuite.core.api;

import java.time.Instant;

public record SuiteApiResponse<T>(
        boolean ok,
        String code,
        String message,
        T data,
        Instant timestamp
) {
    public static <T> SuiteApiResponse<T> ok(T data) {
        return new SuiteApiResponse<>(true, "ok", "ok", data, Instant.now());
    }

    public static <T> SuiteApiResponse<T> ok(String message, T data) {
        return new SuiteApiResponse<>(true, "ok", message, data, Instant.now());
    }

    public static <T> SuiteApiResponse<T> failed(String code, String message, T data) {
        return new SuiteApiResponse<>(false, code, message, data, Instant.now());
    }
}
