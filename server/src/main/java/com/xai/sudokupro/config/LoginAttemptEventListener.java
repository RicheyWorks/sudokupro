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

    // Both handlers pass the attempted username as well as the address, so the counter is
    // scoped to (address, account). Keyed on the address alone, a success for ANY account
    // wiped the counter — see LoginAttemptLimiter.scope.
    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String user = username(event.getAuthentication());
        remoteAddress(event.getAuthentication().getDetails())
            .ifPresent(addr -> limiter.recordFailure(addr, user));
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String user = username(event.getAuthentication());
        remoteAddress(event.getAuthentication().getDetails())
            .ifPresent(addr -> limiter.recordSuccess(addr, user));
    }

    private static String username(org.springframework.security.core.Authentication auth) {
        return auth == null ? null : String.valueOf(auth.getName());
    }

    private java.util.Optional<String> remoteAddress(Object details) {
        if (details instanceof WebAuthenticationDetails wad) {
            return java.util.Optional.ofNullable(wad.getRemoteAddress());
        }
        return java.util.Optional.empty();
    }
}
