package com.xai.sudokupro.controller;

import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.push.DeviceTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock private DeviceTokenStore deviceTokenStore;
    @Mock private AuthService authService;

    private NotificationController controller;

    @BeforeEach
    void setUp() {
        controller = new NotificationController(deviceTokenStore, authService);
        when(authService.getCurrentPlayerId()).thenReturn("richmond");
    }

    @Test
    void registerStoresTheTokenForTheAuthenticatedPlayer() {
        var response = controller.register(new NotificationController.DeviceTokenRequest("fcm-tok-9"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(deviceTokenStore).register("richmond", "fcm-tok-9");
    }

    @Test
    void unregisterRemovesTheCallersToken() {
        assertEquals(HttpStatus.NO_CONTENT, controller.unregister().getStatusCode());
        verify(deviceTokenStore).remove("richmond");
    }
}
