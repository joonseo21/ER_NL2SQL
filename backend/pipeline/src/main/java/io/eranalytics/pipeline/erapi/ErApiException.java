package io.eranalytics.pipeline.erapi;

public class ErApiException extends RuntimeException {
    private final int statusCode;

    public ErApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean retryable() {
        return statusCode == 403 || statusCode == 429 || statusCode >= 500;
    }
}
