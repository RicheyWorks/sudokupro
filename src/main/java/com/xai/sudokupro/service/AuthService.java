package com.xai.sudokupro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private static final String ANONYMOUS = "anonymous";

    public String getCurrentPlayerId() {
        if (isAuthenticated()) {
            return SecurityContextHolder.getContext().getAuthentication().getName();
        }
        logger.debug("No authenticated user found, returning anonymous");
        return ANONYMOUS;
    }

    /**
     * Returns true only for a fully authenticated, non-anonymous principal.
     * Checks against {@link AnonymousAuthenticationToken} rather than comparing
     * principal names — Spring Security uses "anonymousUser" by default, not "anonymous".
     */
    public boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null
            && auth.isAuthenticated()
            && !(auth instanceof AnonymousAuthenticationToken);
    }
}
