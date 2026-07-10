package com.xai.sudokupro.config;

import com.xai.sudokupro.service.LoginAttemptLimiter;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Feeds {@link LoginAttemptLimiter} from Spring Security's authentication events, which
 * fire automatically for every HTTP Basic attempt. {@code WebAuthenticationDetails} (set by
 * {@code BasicAuthenticationFilter}'s default {@code AuthenticationDetailsSource}) carries
 * the same {@code request.getRemoteAddr()} value {@link LoginAttemptFilter} keys on, so
 * successes and failures always land on the same counter.
 */
@Component
public class LoginAttemptEventListener {

    private final LoginAttemptLimiter limiter;

    public LoginAttemptEventListener(LoginAttemptLimiter limiter) {
        this.limiter = limiter;
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        remoteAddress(event.getAuthentication().getDetails()).ifPresent(limiter::recordFailure);
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        remoteAddress(event.getAuthentication().getDetails()).ifPresent(limiter::recordSuccess);
    }

    private java.util.Optional<String> remoteAddress(Object details) {
        if (details instanceof WebAuthenticationDetails wad) {
            return java.util.Optional.ofNullable(wad.getRemoteAddress());
        }
        return java.util.Optional.empty();
    }
}
