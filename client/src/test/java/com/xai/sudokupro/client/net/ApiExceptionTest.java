package com.xai.sudokupro.client.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiExceptionTest {

    @Test
    void status401And403AreAuthFailures() {
        assertTrue(new ApiException(401, "nope").isAuthFailure());
        assertTrue(new ApiException(403, "nope").isAuthFailure());
    }

    @Test
    void otherStatusesAreNotAuthFailures() {
        assertFalse(new ApiException(404, "not found").isAuthFailure());
        assertFalse(new ApiException(500, "boom").isAuthFailure());
    }

    @Test
    void transportFailureConstructorReportsNoHttpStatus() {
        ApiException e = new ApiException("cannot reach server", new java.io.IOException("refused"));
        assertEquals(-1, e.status());
        assertFalse(e.isAuthFailure());
        assertNotNull(e.getCause());
    }
}
