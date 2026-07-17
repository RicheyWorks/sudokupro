package com.xai.sudokupro.controller;

import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.auth.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private AccountService accountService;
    @Mock private AuthService authService;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(accountService, authService);
    }

    @Test
    void registerMaps201And400And409() {
        assertEquals(HttpStatus.CREATED,
            controller.register(new AuthController.RegisterRequest("richmond", "long-password-1")).getStatusCode());

        doThrow(new IllegalArgumentException("bad name")).when(accountService).register("x", "long-password-1");
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.register(new AuthController.RegisterRequest("x", "long-password-1")).getStatusCode());

        doThrow(new IllegalStateException("taken")).when(accountService).register("taken", "long-password-1");
        assertEquals(HttpStatus.CONFLICT,
            controller.register(new AuthController.RegisterRequest("taken", "long-password-1")).getStatusCode());
    }

    @Test
    void passwordChangeUsesTheAuthenticatedIdentity() {
        when(authService.getCurrentPlayerId()).thenReturn("richmond");

        assertEquals(HttpStatus.OK, controller.changePassword(
            new AuthController.PasswordChangeRequest("old-password-1", "new-password-1")).getStatusCode());
        verify(accountService).changePassword("richmond", "old-password-1", "new-password-1");

        doThrow(new SecurityException("wrong")).when(accountService)
            .changePassword("richmond", "bad", "new-password-1");
        assertEquals(HttpStatus.FORBIDDEN, controller.changePassword(
            new AuthController.PasswordChangeRequest("bad", "new-password-1")).getStatusCode());
    }
}
