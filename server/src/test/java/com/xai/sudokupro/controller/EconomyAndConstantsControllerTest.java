package com.xai.sudokupro.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link EconomyController} and {@link ConstantsAdminController}.
 *
 * <h2>Defect classes this protects against</h2>
 * <ol>
 *   <li><b>Cross-account wallet access.</b> Both economy handlers pick the acting player
 *       from {@code AuthService.getCurrentPlayerId()} — i.e. the authenticated Principal —
 *       and nothing in the request can override it. The recurring bug in this codebase is
 *       a handler that takes the player from a caller-controlled {@code playerId} instead,
 *       which lets anyone read (and in the spend paths, drain) somebody else's wallet.
 *       {@link #walletIsTheAuthenticatedCallersOwnAndNobodyElses()} pins the wallet to the
 *       Principal by giving two accounts DIFFERENT, hand-set balances and checking each
 *       caller sees only its own; {@link #aPlayerIdRequestParameterCannotOverrideThePrincipal()}
 *       is the direct guard against the parameter being (re)introduced.</li>
 *   <li><b>Admin config exposure.</b> {@code /admin/constants} publishes the XP/economy
 *       configuration and its integrity hash. It carries NO {@code @PreAuthorize} — and it
 *       could not usefully carry one, because this application never enables method
 *       security ({@code @EnableMethodSecurity} appears nowhere), so such an annotation
 *       would be a silent no-op. The ONLY thing standing between an ordinary player and
 *       this data is the {@code /admin/**} → {@code hasRole("ADMIN")} matcher in
 *       {@code SecurityConfig}, which these tests exercise for real (see
 *       {@link ControllerSliceTestBase} on why the profile matters).</li>
 *   <li><b>Unauthenticated reads.</b> Nothing here is public.</li>
 * </ol>
 */
class EconomyAndConstantsControllerTest extends ControllerSliceTestBase {

    private String rich;
    private String poor;

    @BeforeEach
    void setUp() throws Exception {
        // Fresh accounts per test method — never inherited from another method's side effects.
        rich = freshPlayer();
        poor = freshPlayer();
        // Hand-set, deliberately distinct balances. 4242 and 7 are arbitrary constants
        // chosen so neither can be confused with the 15-gem signing bonus or with each other.
        giveGems(rich, 4242);
        giveGems(poor, 7);
    }

    // ---------------- EconomyController: ownership ----------------

    @Test
    @DisplayName("GET /api/economy/wallet returns the caller's own wallet, never another player's")
    void walletIsTheAuthenticatedCallersOwnAndNobodyElses() throws Exception {
        mockMvc.perform(get("/api/economy/wallet").with(as(rich)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playerId").value(rich))
            .andExpect(jsonPath("$.gems").value(4242));

        // Same endpoint, same instant, different Principal -> different wallet.
        mockMvc.perform(get("/api/economy/wallet").with(as(poor)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playerId").value(poor))
            .andExpect(jsonPath("$.gems").value(7));
    }

    /**
     * The impersonation guard proper.
     *
     * <p>A {@code playerId} request parameter that is not cross-checked against the
     * Principal is the bug that has been found repeatedly in this codebase. There is no
     * such parameter here today; this test fails the moment one is honoured, because the
     * response must keep describing the CALLER even when the request loudly asks for
     * someone else.
     */
    @Test
    void aPlayerIdRequestParameterCannotOverrideThePrincipal() throws Exception {
        for (String smuggle : new String[]{"?playerId=" + rich, "?player=" + rich, "?as=" + rich,
                                           "?username=" + rich, "?user=" + rich}) {
            mockMvc.perform(get("/api/economy/wallet" + smuggle).with(as(poor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(poor))
                .andExpect(jsonPath("$.gems").value(7));
        }
    }

    @Test
    void achievementsBelongToTheCaller() throws Exception {
        // A freshly provisioned wallet has every achievement locked; unlocking one for
        // `rich` only must not change what `poor` sees.
        inTransaction(() -> {
            var wallet = economyService.walletFor(rich);
            var achievements = wallet.getAchievements();
            achievements.put("DuelChampion", true);
            wallet.setAchievements(achievements);
            userRepository.save(wallet);
        });

        mockMvc.perform(get("/api/economy/achievements").with(as(rich)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.DuelChampion").value(true));

        mockMvc.perform(get("/api/economy/achievements").with(as(poor)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.DuelChampion").value(false));
    }

    @Test
    void walletDoesNotLeakTheStoredPasswordHash() throws Exception {
        String body = mockMvc.perform(get("/api/economy/wallet").with(as(rich)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertFalse(body.toLowerCase().contains("password"), "wallet payload leaked a credential field: " + body);
        assertFalse(body.contains("$2a$"), "wallet payload leaked a BCrypt hash: " + body);
    }

    // ---------------- EconomyController: authentication ----------------

    @Test
    void economyEndpointsRejectAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/economy/wallet")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/economy/achievements")).andExpect(status().isUnauthorized());
    }

    @Test
    void economyEndpointsRejectABadPassword() throws Exception {
        mockMvc.perform(get("/api/economy/wallet")
                .with(org.springframework.security.test.web.servlet.request
                    .SecurityMockMvcRequestPostProcessors.httpBasic(rich, "not-the-password")))
            .andExpect(status().isUnauthorized());
    }

    // ---------------- ConstantsAdminController: authorization ----------------

    @Test
    @DisplayName("/admin/constants** is forbidden to an ordinary authenticated player")
    void adminConstantsAreForbiddenToAnOrdinaryPlayer() throws Exception {
        // 403, not 401: the account genuinely exists and authenticates (proved by the
        // economy tests above using the same credentials), it simply lacks ROLE_ADMIN.
        // Asserting 403 rather than "not 200" is what makes a weakening of hasRole("ADMIN")
        // to authenticated() visible.
        mockMvc.perform(get("/admin/constants").with(as(poor))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/constants/hash").with(as(poor))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/constants/export").with(as(poor))).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/constants/reload").with(as(poor)).with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminConstantsAreUnreachableAnonymously() throws Exception {
        mockMvc.perform(get("/admin/constants")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/constants/hash")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/constants/export")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/constants/reload").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void theAdminMutationEndpointStillRequiresACsrfToken() throws Exception {
        // Authenticated as ADMIN but tokenless: CSRF must still refuse it.
        mockMvc.perform(post("/admin/constants/reload").with(ADMIN)).andExpect(status().isForbidden());
    }

    // ---------------- ConstantsAdminController: content ----------------

    @Test
    void adminSeesTheXpConfigurationBlock() throws Exception {
        // 1000 / 50 are the declared defaults of Constants.xpPerLevel / xpPerSolveEasy;
        // the DifficultyTuner adjustment is 0 in this profile.
        mockMvc.perform(get("/admin/constants").with(ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.['XP Config'].['XP Per Level']").value(1000))
            .andExpect(jsonPath("$.['XP Config'].['XP Per Solve Easy']").value(50));
    }

    @Test
    void theIntegrityHashIsAStableSha256() throws Exception {
        String first = mockMvc.perform(get("/admin/constants/hash").with(ADMIN))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/admin/constants/hash").with(ADMIN))
            .andReturn().getResponse().getContentAsString();

        // Implementation-independent invariants: 64 lowercase hex characters (SHA-256),
        // and stable across calls because no request mutates the configuration.
        assertTrue(first.matches("[0-9a-f]{64}"), "not a SHA-256 hex digest: " + first);
        assertTrue(first.equals(second), "integrity hash changed between two reads");
    }

    @Test
    void theExportIsTheRealConfigurationAndCarriesNoSecrets() throws Exception {
        String body = mockMvc.perform(get("/admin/constants/export").with(ADMIN))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"xpPerLevel\":1000"), "export did not contain the real config: " + body);
        assertTrue(body.contains("\"gemCostPowerupHint\""), "export did not contain the gem prices: " + body);
        // Constants is a @ConfigurationProperties bean bound from the environment; if a
        // credential ever gets bound into it, this dump would hand it to every admin call.
        assertFalse(body.toLowerCase().contains("password"), "export leaked a credential: " + body);
        assertFalse(body.toLowerCase().contains("datasource"), "export leaked datasource config: " + body);
    }

    /**
     * {@code POST /admin/constants/reload} is a stub that returns 200 "Reload triggered"
     * without reloading anything. That is a documentation/behaviour mismatch rather than a
     * security defect, so it is pinned rather than "fixed": the point of the assertion is
     * that if somebody makes it do real work, they must revisit this test — and with it the
     * question of whether an unvalidated reload can install negative costs or zero divisors.
     */
    @Test
    void reloadIsAdminReachableAndCurrentlyANoOp() throws Exception {
        String before = mockMvc.perform(get("/admin/constants/hash").with(ADMIN))
            .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/admin/constants/reload").with(ADMIN).with(csrf()))
            .andExpect(status().isOk());

        String after = mockMvc.perform(get("/admin/constants/hash").with(ADMIN))
            .andReturn().getResponse().getContentAsString();
        assertTrue(before.equals(after), "reload changed the configuration but claims to be a stub");
    }
}
