package org.javaup.agent.llm;

import lombok.Getter;

@Getter
public class LlmClientException extends RuntimeException {

    private final int statusCode;

    private final boolean retryable;

    public LlmClientException(String message, int statusCode, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public LlmClientException(String message, int statusCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }
}
