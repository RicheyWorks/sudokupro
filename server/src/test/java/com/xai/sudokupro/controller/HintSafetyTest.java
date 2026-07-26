package com.xai.sudokupro.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Defect class: <b>a state-changing, money-spending operation exposed behind a method the web
 * declares to be safe.</b>
 *
 * <p>{@code GET /api/game/hint} charged gems, incremented the board's hint count (which costs
 * the player the clean-solve bonus and the {@code CleanSolver} achievement), and persisted the
 * board. RFC 9110 §9.2.1 makes GET safe by definition, and the whole ecosystem acts on that:
 * a browser reload, a bfcache restore, a link prefetch, a corporate proxy's warm-up fetch, or
 * a crawler that finds the URL in a log all replay a GET without the user asking. Every replay
 * spent five of the player's gems and silently downgraded their solve.
 *
 * <p>The fix has two halves and this class covers both:
 * <ol>
 *   <li>{@code POST /api/game/hint} is now the canonical verb for "spend gems, give me a new
 *       hint". Asserting it is routed proves the new mapping is actually wired, not merely
 *       written.</li>
 *   <li>The GET is kept working for already-shipped clients (the JavaFX desktop app under
 *       {@code client/} ships against it), but is now <em>safe and idempotent</em>: repeating
 *       it against an unchanged board replays the hint already issued for that board state
 *       instead of buying another one. A prefetch or reload therefore costs nothing. It also
 *       advertises its own deprecation so the migration is visible rather than silent.</li>
 * </ol>
 */
class HintSafetyTest extends ControllerSliceTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Cost of one hint — {@code sudokupro.economy.hint-cost}, default 5. */
    private static final int HINT_COST = 5;

    private String newGame(String player) throws Exception {
        String body = mockMvc.perform(post("/api/game/new")
                .param("difficulty", "3")
                .with(as(player)).with(csrf()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("gameId").asText();
    }

    private int gemsOf(String player) {
        return userRepository.findByUsername(player).orElseThrow().getGems();
    }

    private int hintCountOf(String player, String gameId) throws Exception {
        String body = mockMvc.perform(get("/api/game/" + gameId).with(as(player)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("hintCount").asInt();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
        return org.springframework.security.test.web.servlet.request
            .SecurityMockMvcRequestPostProcessors.csrf();
    }

    /**
     * The new, honest verb must exist and work. Before the fix the route did not exist and
     * Spring answered 405 Method Not Allowed.
     */
    @Test
    void postIsTheCanonicalHintVerb() throws Exception {
        String player = freshPlayer();
        giveGems(player, 100);
        String gameId = newGame(player);

        String body = mockMvc.perform(post("/api/game/hint")
                .param("gameId", gameId)
                .with(as(player)).with(csrf()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode node = JSON.readTree(body);
        assertThat(node.has("hint")).as("POST /api/game/hint must return a hint payload").isTrue();
        assertThat(node.get("hint").asText()).isNotBlank();
    }

    /**
     * The core safety property: replaying the GET must not spend anything.
     *
     * <p>This is the prefetcher / reload / crawler scenario. Before the fix two identical GETs
     * against an unchanged board cost {@code 2 * HINT_COST} gems and pushed hintCount to 2.
     */
    @Test
    void repeatingTheGetDoesNotSpendGemsAgain() throws Exception {
        String player = freshPlayer();
        giveGems(player, 100);
        String gameId = newGame(player);

        int before = gemsOf(player);

        String first = mockMvc.perform(get("/api/game/hint").param("gameId", gameId).with(as(player)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        int afterFirst = gemsOf(player);
        int hintsAfterFirst = hintCountOf(player, gameId);

        // No move in between: the board is byte-for-byte what it was.
        String second = mockMvc.perform(get("/api/game/hint").param("gameId", gameId).with(as(player)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        int afterSecond = gemsOf(player);
        int hintsAfterSecond = hintCountOf(player, gameId);

        assertThat(before - afterFirst)
            .as("the first hint is a genuine purchase and should cost exactly one hint")
            .isEqualTo(HINT_COST);
        assertThat(afterSecond)
            .as("a replayed GET spent the player's gems — GET is not safe")
            .isEqualTo(afterFirst);
        assertThat(hintsAfterSecond)
            .as("a replayed GET raised hintCount, costing the clean-solve bonus the player "
                + "never asked to give up")
            .isEqualTo(hintsAfterFirst);
        assertThat(JSON.readTree(second).get("hint").asText())
            .as("an idempotent GET must replay the same answer, not a different one")
            .isEqualTo(JSON.readTree(first).get("hint").asText());
    }

    /**
     * Clients still calling the GET must be told, in-band, that it is going away — otherwise
     * "deprecated" is a comment in a file nobody reading the API will ever see.
     */
    @Test
    void theDeprecatedGetAdvertisesItself() throws Exception {
        String player = freshPlayer();
        giveGems(player, 100);
        String gameId = newGame(player);

        var response = mockMvc.perform(get("/api/game/hint").param("gameId", gameId).with(as(player)))
            .andExpect(status().isOk())
            .andReturn().getResponse();

        assertThat(response.getHeader("Deprecation"))
            .as("RFC 8594 Deprecation header")
            .isEqualTo("true");
        assertThat(response.getHeader("Link"))
            .as("Link header should point callers at the replacement")
            .contains("/api/game/hint");
        assertThat(response.getHeader("Cache-Control"))
            .as("a hint response must never be cached by an intermediary and replayed")
            .contains("no-store");
    }

    /**
     * The other half of idempotency: POST is the unsafe verb, so it must genuinely buy a new
     * hint each time. Without this a "fix" that simply memoised everything would pass the test
     * above while quietly breaking the feature — a player could never get a second hint.
     */
    @Test
    void postBuysAFreshHintEveryTime() throws Exception {
        String player = freshPlayer();
        giveGems(player, 100);
        String gameId = newGame(player);

        int before = gemsOf(player);
        mockMvc.perform(post("/api/game/hint").param("gameId", gameId).with(as(player)).with(csrf()))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/game/hint").param("gameId", gameId).with(as(player)).with(csrf()))
            .andExpect(status().isOk());

        assertThat(before - gemsOf(player))
            .as("two explicit POSTed hint purchases must cost two hints")
            .isEqualTo(2 * HINT_COST);
        assertThat(hintCountOf(player, gameId)).isEqualTo(2);
    }
}
