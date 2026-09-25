package io.eranalytics.pipeline;

final class ApiException extends RuntimeException {
    private final int statusCode;

    ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    int statusCode() {
        return statusCode;
    }

    boolean retryable() {
        return statusCode == 403 || statusCode == 429 || statusCode >= 500;
    }
}
