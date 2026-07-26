package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.economy.EconomyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared wiring for the controller-slice tests.
 *
 * <h2>Why these tests boot the whole application</h2>
 * {@link com.xai.sudokupro.config.SecurityConfig} is annotated {@code @Profile("!test")}.
 * Under the {@code test} profile it is NOT loaded at all, so Spring Boot's fallback
 * "secure everything with a generated password" chain applies instead — a plain
 * {@code @WebMvcTest} or Mockito unit test therefore proves NOTHING about this
 * application's authorization rules: the {@code /admin/**} → ROLE_ADMIN matcher, the
 * {@code permitAll} list and the CSRF configuration all live in the bean that is
 * switched off. Every subclass consequently runs under the {@code dev} profile (which
 * satisfies {@code !test} and disables {@code SecretsGuard}) with
 * {@code @AutoConfigureMockMvc}, so the REAL filter chain is in front of the handlers and
 * an assertion of 401/403 means something.
 *
 * <p>The {@code dev} profile also fixes the admin credentials
 * ({@code admin} / {@code secret}, see {@code application-dev.properties}), which is what
 * {@link #ADMIN} authenticates with.
 *
 * <h2>Fixture isolation</h2>
 * The {@code @TestPropertySource} block is byte-identical across every subclass so Spring's
 * context cache serves ONE application context to all of them — otherwise each class would
 * pay a fresh boot. The flip side is that the H2 database, the in-memory Redis-degrade maps
 * and the leaderboard cache are shared by every test in the slice. Tests therefore must
 * never assume a globally empty world; each one mints its own accounts through
 * {@link #freshPlayer()} in its own {@code @BeforeEach} and asserts only about those.
 * (A previous pass of this project was bitten by exactly the opposite: an account created
 * as a side effect of one test method made a later test return 401 where it expected 403.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:ctrlslice;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "sudokupro.ui.enabled=false",
    // These suites mint fixture accounts in bulk from a single (mock) client address,
    // which is exactly what RegistrationAttemptLimiter exists to stop in production.
    // Lift the quota here so the throttle does not turn fixture setup into 429s; the
    // throttle itself is exercised for real by security/RegistrationThrottleTest.
    "sudokupro.security.register.max-attempts=1000000"
})
abstract class ControllerSliceTestBase {

    /** Registration password used for every minted fixture account. */
    protected static final String PASSWORD = "password123";

    /** The dev-profile admin, per {@code application-dev.properties}. */
    protected static final RequestPostProcessor ADMIN = httpBasic("admin", "secret");

    /** Process-wide counter so two test methods can never collide on a username. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /**
     * Per-JVM-run salt for fixture usernames.
     *
     * <p>Not decoration — a counter alone is NOT enough here. The H2 database is recreated
     * per run, but {@code FriendService} keeps pending requests in a REAL Redis (this
     * environment has one; the in-memory degrade path only engages when it is down) under
     * {@code sudokupro:friends:pending:<username>} with a FOURTEEN-DAY TTL. With names like
     * {@code slice1, slice2, ...} restarting from 1 every run, a later run inherits the
     * previous run's inbox for the same name: observed live as a friend-direction assertion
     * passing under a mutation that should have broken it, and later as the same assertion
     * failing with no mutation applied at all. The salt makes every run's namespace disjoint.
     */
    private static final String RUN = Long.toString(
        java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40), 36);

    @Autowired protected MockMvc mockMvc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected EconomyService economyService;

    /**
     * Registers a brand-new player and returns its username.
     *
     * <p>Names are unique per call, so no test method can be affected by state another
     * test method left behind (friend edges, pending requests, power-up inventory, gems).
     */
    protected String freshPlayer() throws Exception {
        String username = "slice" + RUN + "x" + SEQ.incrementAndGet();
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .status().isCreated());
        return username;
    }

    protected RequestPostProcessor as(String username) {
        return httpBasic(username, PASSWORD);
    }

    /** Provisions the wallet row and forces its gem balance, for tests that need to afford things. */
    protected void giveGems(String username, int gems) {
        inTransaction(() -> {
            User wallet = economyService.walletFor(username);
            wallet.setGems(gems);
            userRepository.save(wallet);
        });
    }

    /**
     * Runs fixture setup inside a transaction.
     *
     * <p>{@code User} maps {@code achievements}/{@code powerUps}/{@code friends} as LAZY
     * element collections, so touching them on the detached entity that
     * {@code EconomyService.walletFor} hands back outside a transaction throws
     * {@code LazyInitializationException}. Test fixtures that read or edit those maps must
     * therefore run in a session.
     */
    protected void inTransaction(Runnable work) {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
            .executeWithoutResult(status -> work.run());
    }

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;
}
