package com.xai.sudokupro.model.api;

/**
 * Response of {@code GET /api/session}: identifies the authenticated player and
 * hands the client the CSRF token it must echo on mutating requests.
 * (The token is also double-submitted via the XSRF-TOKEN cookie.)
 */
public record SessionInfo(String playerId, String csrfHeaderName, String csrfToken) {}
