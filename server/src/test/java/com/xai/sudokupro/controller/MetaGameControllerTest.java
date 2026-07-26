package com.xai.sudokupro.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MetaGameController} — the largest controller in this slice: weekly tournament,
 * quarterly seasons and the power-up shop.
 *
 * <h2>Defect classes this protects against</h2>
 * <ol>
 *   <li><b>Out-of-range parameters answered with HTTP 500 instead of 400.</b> The class
 *       carries {@code @Validated}, so {@code @Min}/{@code @Max} are enforced by an AOP
 *       proxy that raises {@code ConstraintViolationException} — an exception Spring MVC
 *       has no default mapping for. Verified live before the fix:
 *       {@code POST /api/tournament/0/join}, {@code /6/join}, {@code /-1/join} and
 *       {@code GET /api/tournament/standings?limit=0|9999} all escaped the dispatcher as
 *       500. {@code SudokuGameController} already carried the local
 *       {@code @ExceptionHandler} that fixes this; this controller did not.</li>
 *   <li><b>Spending or inspecting another player's inventory.</b> Every handler takes the
 *       acting player from the Principal. The tests pin that by giving two accounts
 *       different inventories and by attempting the impersonation shape explicitly.</li>
 *   <li><b>Using a power-up against a game or a player you have no claim on.</b>
 *       {@code EXTRA_LIFE}/{@code REVEAL_CELL} must refuse a game you do not own;
 *       {@code FREEZE} must refuse a target you are not in an active duel with (otherwise
 *       any name off the public leaderboard can have its input locked).</li>
 *   <li><b>Tournament boards issued under the wrong owner.</b></li>
 *   <li><b>Unauthenticated / CSRF-less access.</b></li>
 * </ol>
 */
class MetaGameControllerTest extends ControllerSliceTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Catalogue prices, copied by hand from {@code PowerUpService.CATALOG}. */
    private static final int EXTRA_LIFE_PRICE = 15;
    private static final int FREEZE_PRICE = 25;

    private String me;
    private String other;

    @BeforeEach
    void setUp() throws Exception {
        me = freshPlayer();
        other = freshPlayer();
    }

    // ---------------- parameter validation: 400, never 500 ----------------

    /**
     * Reproduction of the headline bug. Before the {@code @ExceptionHandler} was added,
     * each of these produced {@code jakarta.validation.ConstraintViolationException}
     * propagating out of the dispatcher, i.e. HTTP 500.
     */
    @Test
    @DisplayName("out-of-range tournament puzzle numbers are 400, not 500")
    void joiningAnOutOfRangeTournamentPuzzleIsABadRequest() throws Exception {
        for (String puzzle : new String[]{"0", "6", "-1", "2147483647"}) {
            mockMvc.perform(post("/api/tournament/" + puzzle + "/join").with(as(me)).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Parameter"));
        }
    }

    @Test
    @DisplayName("out-of-range standings limits are 400, not 500")
    void standingsRejectsAnOutOfRangeLimitAsABadRequest() throws Exception {
        for (String limit : new String[]{"0", "-5", "101", "9999"}) {
            mockMvc.perform(get("/api/tournament/standings?limit=" + limit).with(as(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Parameter"));
        }
    }

    @Test
    void nonNumericParametersAreAlsoABadRequestRatherThanAServerError() throws Exception {
        mockMvc.perform(post("/api/tournament/abc/join").with(as(me)).with(csrf()))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/tournament/standings?limit=%20").with(as(me)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void inRangeParametersStillSucceedSoTheHandlerDidNotSwallowEverything() throws Exception {
        // Guards against "fixing" the 500 by making the constraint stop firing at all.
        mockMvc.perform(post("/api/tournament/5/join").with(as(me)).with(csrf()))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/tournament/standings?limit=100").with(as(me)))
            .andExpect(status().isOk());
    }

    // ---------------- tournament ownership ----------------

    @Test
    @DisplayName("a joined tournament board belongs to the caller")
    void tournamentJoinIssuesABoardOwnedByTheAuthenticatedCaller() throws Exception {
        String body = mockMvc.perform(post("/api/tournament/1/join").with(as(me)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playerId").value(me))
            .andReturn().getResponse().getContentAsString();

        // The per-player copy id is "<template>:<playerId>", so the owner is baked into the
        // key as well as the field: both must name the caller, not the other account.
        String gameId = JSON.readTree(body).get("gameId").asText();
        assertTrue(gameId.endsWith(":" + me), "tournament game id is not the caller's: " + gameId);
        assertTrue(!gameId.contains(other), "tournament game id leaked another player: " + gameId);
    }

    @Test
    void twoPlayersJoiningTheSamePuzzleGetSeparateBoards() throws Exception {
        String mine = JSON.readTree(mockMvc.perform(post("/api/tournament/2/join").with(as(me)).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("gameId").asText();
        String theirs = JSON.readTree(mockMvc.perform(post("/api/tournament/2/join").with(as(other)).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("gameId").asText();

        org.junit.jupiter.api.Assertions.assertNotEquals(mine, theirs,
            "both players were handed the SAME tournament board — one can finish the other's puzzle");
    }

    @Test
    void tournamentStatusDescribesTheCallerAndStartsEmpty() throws Exception {
        // A brand-new account has completed none of the five puzzles. 5 and the
        // puzzle numbering 1..5 are hand-derived from PUZZLES_PER_WEEK.
        mockMvc.perform(get("/api/tournament").with(as(me)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.completed").value(0))
            .andExpect(jsonPath("$.ranked").value(false))
            .andExpect(jsonPath("$.totalSeconds").value(0))
            .andExpect(jsonPath("$.puzzles.length()").value(5))
            .andExpect(jsonPath("$.puzzles[0].puzzle").value(1))
            .andExpect(jsonPath("$.puzzles[4].puzzle").value(5))
            .andExpect(jsonPath("$.weekId").exists());
    }

    // ---------------- seasons ----------------

    /**
     * Invariants rather than a recomputation of the season formula: the id must name a
     * quarter, and the end date must be the first day of a quarter that is still in the
     * future. A rollover that mislabels or mis-dates the boundary breaks one of these.
     */
    @Test
    void seasonReportsAQuarterIdAndAQuarterBoundaryEndDate() throws Exception {
        String body = mockMvc.perform(get("/api/season").with(as(me)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode json = JSON.readTree(body);

        String seasonId = json.get("seasonId").asText();
        assertTrue(seasonId.matches("\\d{4}-Q[1-4]"), "not a quarter id: " + seasonId);

        LocalDate endsOn = LocalDate.parse(json.get("endsOn").asText());
        assertEquals(1, endsOn.getDayOfMonth(), "a season must end on the 1st, got " + endsOn);
        assertTrue(endsOn.getMonthValue() % 3 == 1,
            "a season must end on a quarter boundary (Jan/Apr/Jul/Oct), got " + endsOn);
        assertTrue(endsOn.isAfter(LocalDate.now()), "the current season already ended: " + endsOn);
    }

    // ---------------- power-up shop: ownership ----------------

    @Test
    @DisplayName("the shop shows the CALLER's inventory, not a shared or borrowed one")
    void shopInventoryIsPerCaller() throws Exception {
        giveGems(me, EXTRA_LIFE_PRICE);

        mockMvc.perform(post("/api/powerups/buy/EXTRA_LIFE").with(as(me)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.type").value("EXTRA_LIFE"))
            .andExpect(jsonPath("$.held").value(1));

        mockMvc.perform(get("/api/powerups").with(as(me)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.catalog.EXTRA_LIFE").value(EXTRA_LIFE_PRICE))
            .andExpect(jsonPath("$.inventory.EXTRA_LIFE").value(1));

        // The other account bought nothing and must hold nothing.
        mockMvc.perform(get("/api/powerups").with(as(other)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inventory.EXTRA_LIFE").doesNotExist());
    }

    @Test
    void aPlayerIdParameterCannotRedirectAPurchaseToAnotherWallet() throws Exception {
        giveGems(me, EXTRA_LIFE_PRICE);
        giveGems(other, 0);

        mockMvc.perform(post("/api/powerups/buy/EXTRA_LIFE?playerId=" + other + "&as=" + other)
                .with(as(me)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.held").value(1));

        // The gems came out of the CALLER's wallet and the item landed in the CALLER's bag.
        assertEquals(0, economyService.walletFor(me).getGems(), "the caller was not charged");
        assertEquals(0, economyService.walletFor(other).getGems(), "another player's wallet was touched");
        mockMvc.perform(get("/api/powerups").with(as(other)))
            .andExpect(jsonPath("$.inventory.EXTRA_LIFE").doesNotExist());
    }

    @Test
    void buyingWithoutEnoughGemsIsPaymentRequiredAndChargesNothing() throws Exception {
        giveGems(me, EXTRA_LIFE_PRICE - 1);

        mockMvc.perform(post("/api/powerups/buy/EXTRA_LIFE").with(as(me)).with(csrf()))
            .andExpect(status().isPaymentRequired())
            .andExpect(jsonPath("$.title").value("Not Enough Gems"));

        assertEquals(EXTRA_LIFE_PRICE - 1, economyService.walletFor(me).getGems(),
            "a refused purchase still moved gems");
        mockMvc.perform(get("/api/powerups").with(as(me)))
            .andExpect(jsonPath("$.inventory.EXTRA_LIFE").doesNotExist());
    }

    @Test
    void buyingAnUnknownPowerUpIsABadRequest() throws Exception {
        giveGems(me, 10_000);

        mockMvc.perform(post("/api/powerups/buy/NOT_A_POWERUP").with(as(me)).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Unknown Power-Up"));

        assertEquals(10_000, economyService.walletFor(me).getGems(),
            "an unknown power-up still charged the wallet");
    }

    // ---------------- power-up use: cross-player boundaries ----------------

    /**
     * The strongest ownership test in this class: {@code me} legitimately holds an
     * EXTRA_LIFE, and points it at a game created by {@code other}. It must be refused —
     * otherwise a purchased power-up becomes a way to alter any board on the platform.
     */
    @Test
    @DisplayName("a power-up cannot be spent on somebody else's game")
    void usingAPowerUpOnAnotherPlayersGameIsForbidden() throws Exception {
        String victimsGame = JSON.readTree(
            mockMvc.perform(post("/api/game/new?difficulty=1").with(as(other)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
            .get("gameId").asText();

        giveGems(me, EXTRA_LIFE_PRICE);
        mockMvc.perform(post("/api/powerups/buy/EXTRA_LIFE").with(as(me)).with(csrf()))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/powerups/use/EXTRA_LIFE?gameId=" + victimsGame)
                .with(as(me)).with(csrf()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.title").value("Not Your Game"));

        // Refused, so the unit must NOT have been consumed.
        mockMvc.perform(get("/api/powerups").with(as(me)))
            .andExpect(jsonPath("$.inventory.EXTRA_LIFE").value(1));
    }

    /**
     * FREEZE locks a player's input for ten seconds and is silent to the victim. It must be
     * confined to an opponent in an ACTIVE duel; without that check any username off the
     * public leaderboard could be frozen at will.
     */
    @Test
    @DisplayName("FREEZE cannot be aimed at a player you are not duelling")
    void freezingSomebodyYouAreNotDuellingIsForbidden() throws Exception {
        giveGems(me, FREEZE_PRICE);
        mockMvc.perform(post("/api/powerups/buy/FREEZE").with(as(me)).with(csrf()))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/powerups/use/FREEZE?target=" + other).with(as(me)).with(csrf()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.title").value("Not Your Game"));

        mockMvc.perform(get("/api/powerups").with(as(me)))
            .andExpect(jsonPath("$.inventory.FREEZE").value(1));
    }

    @Test
    void freezingYourselfIsABadRequest() throws Exception {
        giveGems(me, FREEZE_PRICE);
        mockMvc.perform(post("/api/powerups/buy/FREEZE").with(as(me)).with(csrf()))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/powerups/use/FREEZE?target=" + me).with(as(me)).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    @Test
    void usingAPowerUpYouDoNotHoldIsAConflict() throws Exception {
        mockMvc.perform(post("/api/powerups/use/REVEAL_CELL?gameId=whatever").with(as(me)).with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("Cannot Use"));
    }

    @Test
    void usingAGameScopedPowerUpWithoutAGameIdIsABadRequest() throws Exception {
        giveGems(me, EXTRA_LIFE_PRICE);
        mockMvc.perform(post("/api/powerups/buy/EXTRA_LIFE").with(as(me)).with(csrf()))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/powerups/use/EXTRA_LIFE").with(as(me)).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("This power-up needs a gameId"));
    }

    @Test
    void usingAnUnknownPowerUpIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/powerups/use/NOT_A_POWERUP").with(as(me)).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    // ---------------- authentication / CSRF ----------------

    @Test
    void everyMetaGameEndpointRejectsAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/tournament")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/tournament/standings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/season")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/powerups")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/tournament/1/join").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/powerups/buy/EXTRA_LIFE").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/powerups/use/EXTRA_LIFE").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void mutatingMetaGameCallsWithoutACsrfTokenAreRejectedAndChargeNothing() throws Exception {
        giveGems(me, 10_000);

        mockMvc.perform(post("/api/powerups/buy/EXTRA_LIFE").with(as(me)))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tournament/1/join").with(as(me)))
            .andExpect(status().isForbidden());

        assertEquals(10_000, economyService.walletFor(me).getGems(),
            "a CSRF-rejected purchase still charged the wallet");
    }
}
