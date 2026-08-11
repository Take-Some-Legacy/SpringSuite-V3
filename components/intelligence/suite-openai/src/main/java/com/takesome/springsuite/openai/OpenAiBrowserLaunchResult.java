package com.takesome.springsuite.openai;

public record OpenAiBrowserLaunchResult(
        boolean opened,
        String url,
        String message
) {
}
