package com.xai.sudokupro.service.push;

/**
 * Delivery-provider abstraction for push notifications. The only production
 * implementation is {@link FcmPushSender} (FCM HTTP v1); tests substitute fakes.
 */
public interface PushSender {

    enum PushResult {
        /** Accepted by the provider. */
        SENT,
        /** The device token is dead (unregistered/expired) — callers should drop it. */
        INVALID_TOKEN,
        /** Transient or unexpected failure — token may still be valid. */
        FAILED,
        /** Provider not configured; nothing was attempted. */
        DISABLED
    }

    /** True when the provider is configured and willing to attempt deliveries. */
    boolean isEnabled();

    /**
     * Attempts to deliver one notification to one device.
     *
     * @param deviceToken provider-issued device registration token
     * @param title       notification title
     * @param body        notification body text
     * @param type        application-level type, forwarded as data payload
     */
    PushResult send(String deviceToken, String title, String body, String type);
}
