package com.xai.sudokupro.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the zero-install browser client at its documented entry point.
 *
 * <p>Spring Boot only resolves a directory "welcome page" (index.html) for the
 * context root, not for arbitrary static sub-directories. So while
 * {@code /play/index.html} and {@code /play/app.js} resolved fine,
 * {@code /play/} — the URL the README tells users to open, and the natural thing
 * to type — produced a 404 (which, before {@code /error} was permitted in
 * {@link SecurityConfig}, surfaced to anonymous callers as a confusing 401).
 *
 * <p>Forwarding both {@code /play} and {@code /play/} to the real resource keeps
 * the shipped URL working without weakening the static-resource setup or the CSP.
 */
@Configuration
public class WebClientMvcConfig implements WebMvcConfigurer {

    static final String WEB_CLIENT_INDEX = "forward:/play/index.html";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/play").setViewName(WEB_CLIENT_INDEX);
        registry.addViewController("/play/").setViewName(WEB_CLIENT_INDEX);
    }
}
