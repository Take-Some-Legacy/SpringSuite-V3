package com.takesome.springsuite.openai;



import java.util.Map;



public record OpenAiResponseResult(

        boolean ok,

        int httpStatus,

        String requestId,

        String responseId,

        String model,

        String outputText,

        Map<String, Object> usage,

        String errorCode,

        String errorMessage

) {

    public OpenAiResponseResult {

        requestId = requestId == null ? "" : requestId;

        responseId = responseId == null ? "" : responseId;

        model = model == null ? "" : model;

        outputText = outputText == null ? "" : outputText;

        usage = usage == null ? Map.of() : Map.copyOf(usage);

        errorCode = errorCode == null ? "" : errorCode;

        errorMessage = errorMessage == null ? "" : errorMessage;

    }

}
