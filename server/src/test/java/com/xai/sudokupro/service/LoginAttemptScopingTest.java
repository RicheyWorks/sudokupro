package com.xai.sudokupro.service;

import com.xai.sudokupro.config.LoginAttemptFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Brute-force lockout: the counter's SCOPE, and the filter that enforces it.
 *
 * <p>A mutation audit found both of these unguarded. Reducing
 * {@code LoginAttemptLimiter.scope()} to {@code return remoteAddress;} — which is exactly
 * the documented regression, where any successful login clears every account's counter at
 * that address — left the suite green, because the existing test only calls the legacy
 * single-argument overloads. And short-circuiting {@code LoginAttemptFilter} to skip the
 * 429 entirely also left it green, because the filter appears in no test at all.
 *
 * <p>That second shape is the SecretsGuard failure repeating: the rules were tested while
 * the wiring that applies them was not.
 *
 * <p>Redis is a mock that throws on every call, so these exercise the in-memory fallback —
 * the path a single-replica deployment actually runs.
 */
class LoginAttemptScopingTest {

    private static final int MAX_ATTEMPTS = 5;

    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        limiter = new LoginAttemptLimiter(downRedis, MAX_ATTEMPTS, 60);
    }

    /**
     * The headline regression. Registration is permitAll and CSRF-exempt, so an attacker
     * gets a free account; if a success cleared the whole address, they could guess four
     * passwords, log in as themselves, and repeat forever. Measured before the fix: twelve
     * consecutive wrong-password attempts against one target, zero lockouts.
     */
    @Test
    void aSuccessForOneAccountDoesNotClearAnotherAccountsCounter() {
        String ip = "203.0.113.5";
        for (int i = 0; i < MAX_ATTEMPTS; i++) limiter.recordFailure(ip, "victim");
        assertTrue(limiter.isBlocked(ip, "victim"), "the victim's account should be locked out");

        // The attacker's own account succeeds from the same address.
        limiter.recordSuccess(ip, "attacker-own-account");

        assertTrue(limiter.isBlocked(ip, "victim"),
            "another account's successful login must not reset the victim's counter — "
                + "that is an unlimited online password-guessing oracle");
    }

    /** The legitimate case still works: your own success clears your own counter. */
    @Test
    void aSuccessForTheSameAccountClearsItsOwnCounter() {
        String ip = "203.0.113.6";
        for (int i = 0; i < MAX_ATTEMPTS; i++) limiter.recordFailure(ip, "alice");
        assertTrue(limiter.isBlocked(ip, "alice"));

        limiter.recordSuccess(ip, "alice");

        assertFalse(limiter.isBlocked(ip, "alice"),
            "a player who finally remembers their password must not stay locked out");
    }

    /** Failures against one account must not lock a different account at the same address. */
    @Test
    void oneAccountsFailuresDoNotLockADifferentAccount() {
        String ip = "203.0.113.7";
        for (int i = 0; i < MAX_ATTEMPTS * 2; i++) limiter.recordFailure(ip, "targeted");

        assertTrue(limiter.isBlocked(ip, "targeted"));
        assertFalse(limiter.isBlocked(ip, "bystander"),
            "a household or office sharing one NAT address must not be locked out because "
                + "one member's account was attacked");
    }

    /** The same account at a different address is counted separately. */
    @Test
    void theCounterIsScopedToTheAddressAsWellAsTheAccount() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) limiter.recordFailure("198.51.100.1", "alice");
        assertTrue(limiter.isBlocked("198.51.100.1", "alice"));
        assertFalse(limiter.isBlocked("198.51.100.99", "alice"));
    }

    /** Usernames differing only in case must share one counter, or the cap is trivially bypassed. */
    @Test
    void usernameCasingDoesNotCreateASeparateBudget() {
        String ip = "198.51.100.2";
        for (int i = 0; i < MAX_ATTEMPTS; i++) limiter.recordFailure(ip, "Alice");

        assertTrue(limiter.isBlocked(ip, "alice"),
            "varying the casing of the username must not hand the attacker a fresh budget");
        assertTrue(limiter.isBlocked(ip, "ALICE"));
    }

    // ── the filter that actually enforces it ─────────────────────────────────

    private static HttpServletRequest basicAuthRequest(String user, String pass, String ip) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        String cred = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
        when(req.getHeader("Authorization")).thenReturn("Basic " + cred);
        lenient().when(req.getRemoteAddr()).thenReturn(ip);
        return req;
    }

    /**
     * Regression: the filter had no test whatsoever. Replacing its body with a bare
     * {@code chain.doFilter(...)} — deleting the 429 entirely — left the suite green, so
     * the lockout could have been silently disabled by any refactor.
     */
    @Test
    void theFilterReturns429ForALockedOutAccountAndDoesNotCallTheChain() throws Exception {
        String ip = "203.0.113.10";
        for (int i = 0; i < MAX_ATTEMPTS; i++) limiter.recordFailure(ip, "victim");

        LoginAttemptFilter filter = new LoginAttemptFilter(limiter);
        HttpServletRequest req = basicAuthRequest("victim", "guess", ip);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        StringWriter body = new StringWriter();
        when(resp.getWriter()).thenReturn(new PrintWriter(body));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(429);
        verify(chain, never()).doFilter(any(), any());
        assertTrue(body.toString().contains("Too many failed login attempts"),
            "the caller should be told why, got: " + body);
    }

    /** A different account at the same address must still be let through to authenticate. */
    @Test
    void theFilterLetsAnUnrelatedAccountThroughFromTheSameAddress() throws Exception {
        String ip = "203.0.113.11";
        for (int i = 0; i < MAX_ATTEMPTS; i++) limiter.recordFailure(ip, "victim");

        LoginAttemptFilter filter = new LoginAttemptFilter(limiter);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(basicAuthRequest("bystander", "correct", ip), resp, chain);

        verify(chain).doFilter(any(), any());
        verify(resp, never()).setStatus(429);
    }

    /** Requests with no Basic credential are not login attempts and must pass straight through. */
    @Test
    void theFilterIgnoresRequestsThatCarryNoCredential() throws Exception {
        LoginAttemptFilter filter = new LoginAttemptFilter(limiter);
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn(null);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
        verify(resp, never()).setStatus(anyInt());
    }
}
