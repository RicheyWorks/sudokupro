package com.xai.sudokupro.client.net;

/** Thrown when the server rejects or fails an API call. */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
    }

    public int status() {
        return status;
    }

    public boolean isAuthFailure() {
        return status == 401 || status == 403;
    }
}
