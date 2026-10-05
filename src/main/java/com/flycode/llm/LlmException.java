package com.flycode.llm;

public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class AuthenticationException extends LlmException {

        public AuthenticationException(String message) {
            super(message);
        }
    }

    public static class RateLimitException extends LlmException {
        private final String retryAfter;

        public RateLimitException(String message, String retryAfter) {
            super(message);
            this.retryAfter = retryAfter;
        }

        public String getRetryAfter() { return retryAfter; }
    }

    public static class ContextTooLongException extends LlmException {
        public ContextTooLongException(String message) {
            super(message);
        }
    }

    public static class NetworkException extends LlmException {
        public NetworkException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
