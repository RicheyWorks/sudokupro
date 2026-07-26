package com.xai.sudokupro.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SessionController}, {@link EventsController} and {@link LeaderboardController} —
 * the three read-only surfaces of this slice.
 *
 * <h2>Defect classes this protects against</h2>
 * <ol>
 *   <li><b>Information disclosure about other players.</b> {@code /api/session} must
 *       describe the CALLER (it is the bootstrap a desktop client trusts to learn who it is
 *       logged in as); {@code /api/events} must publish only the event id and end time,
 *       never the {@code SudokuBoard} that {@code EventEngine.EventDetails} holds alongside
 *       it — that board carries the solution; {@code /api/leaderboard} must publish only
 *       the public ranking projection, never credential fields off the {@code users} row.</li>
 *   <li><b>Out-of-range paging answered with HTTP 500 instead of 400.</b>
 *       {@code LeaderboardController} is {@code @Validated}, so its {@code @Min(1) @Max(100)}
 *       raised {@code ConstraintViolationException} — which Spring MVC does not map —
 *       and {@code ?limit=0}, {@code ?limit=-5}, {@code ?limit=101} all came back 500.
 *       Verified live before the fix.</li>
 *   <li><b>Unauthenticated access.</b> Both "public" surfaces are in fact behind
 *       {@code anyRequest().authenticated()}; that must stay true.</li>
 *   <li><b>A broken CSRF bootstrap.</b> {@code /api/session} exists so a non-browser client
 *       can obtain the XSRF-TOKEN cookie; if the deferred token is never materialised the
 *       cookie is not written and every subsequent POST from that client 403s.</li>
 * </ol>
 */
class SessionEventsLeaderboardControllerTest extends ControllerSliceTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private String me;
    private String other;

    @BeforeEach
    void setUp() throws Exception {
        me = freshPlayer();
        other = freshPlayer();
    }

    // ---------------- SessionController ----------------

    @Test
    @DisplayName("GET /api/session names the authenticated caller, never another player")
    void sessionIdentifiesTheCaller() throws Exception {
        mockMvc.perform(get("/api/session").with(as(me)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playerId").value(me));

        mockMvc.perform(get("/api/session").with(as(other)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playerId").value(other));
    }

    @Test
    void aPlayerIdParameterCannotChangeWhoTheSessionSaysYouAre() throws Exception {
        // The desktop client trusts this field to decide which account it is driving; if a
        // request parameter could set it, a caller could convince its own client — and any
        // server-side code that echoes it — that it is somebody else.
        for (String smuggle : new String[]{"?playerId=" + other, "?user=" + other, "?as=" + other}) {
            mockMvc.perform(get("/api/session" + smuggle).with(as(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(me));
        }
    }

    /**
     * The session payload must always carry a usable, non-empty CSRF token.
     *
     * <p>The HEADER NAME and the XSRF-TOKEN cookie are asserted in
     * {@link SessionCsrfBootstrapTest} instead, in its own application context: any sibling
     * class in this shared context that calls {@code SecurityMockMvcRequestPostProcessors
     * .csrf()} permanently replaces the {@code CsrfTokenRepository} on the shared
     * {@code CsrfFilter} bean, which changes both. Asserting them here would make this test
     * pass or fail according to test-class ordering rather than production behaviour.
     */
    @Test
    void sessionAlwaysCarriesANonEmptyCsrfToken() throws Exception {
        String body = mockMvc.perform(get("/api/session").with(as(me)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode json = JSON.readTree(body);
        // hasNonNull, not just asText().isBlank(): Jackson's NullNode.asText() returns the
        // STRING "null", so a handler that stopped materialising the token would sail past a
        // blank check while shipping a useless payload.
        assertTrue(json.hasNonNull("csrfToken"), "csrfToken was null in the session payload: " + body);
        assertTrue(json.hasNonNull("csrfHeaderName"), "csrfHeaderName was null in the session payload: " + body);
        assertFalse(json.get("csrfToken").asText().isBlank(), "no CSRF token in the session payload");
        assertFalse(json.get("csrfHeaderName").asText().isBlank(), "no CSRF header name in the session payload");
    }

    @Test
    void sessionRejectsAnonymousAndBadCredentials() throws Exception {
        mockMvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/session")
                .with(org.springframework.security.test.web.servlet.request
                    .SecurityMockMvcRequestPostProcessors.httpBasic(me, "wrong-password")))
            .andExpect(status().isUnauthorized());
    }

    // ---------------- EventsController ----------------

    @Test
    void eventsRejectAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/events")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("active events are ordered by end time, soonest first")
    void activeEventsAreSortedByEndTime() throws Exception {
        JsonNode events = JSON.readTree(mockMvc.perform(get("/api/events").with(as(me)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        List<LocalDateTime> endTimes = new ArrayList<>();
        events.forEach(e -> endTimes.add(LocalDateTime.parse(e.get("endTime").asText())));

        // EventEngine seeds a daily challenge (+24h), a cosmic duel (+30m) and a drip
        // showdown (+10m) at startup, so the natural map order is NOT sorted; a dropped or
        // reversed comparator shows up here.
        assertTrue(endTimes.size() >= 2,
            "expected the startup events to be active, got " + endTimes.size());
        for (int i = 1; i < endTimes.size(); i++) {
            assertFalse(endTimes.get(i).isBefore(endTimes.get(i - 1)),
                "events are not in ascending end-time order: " + endTimes);
        }
    }

    /**
     * {@code EventEngine.EventDetails} pairs the end time with the generated
     * {@link com.xai.sudokupro.model.SudokuBoard} — including its solution. The wire
     * projection must expose the id and the end time and nothing else; returning the raw
     * details map would hand every authenticated player the answer key.
     */
    @Test
    void eventsExposeOnlyTheIdAndEndTimeAndNeverTheBoard() throws Exception {
        String body = mockMvc.perform(get("/api/events").with(as(me)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode events = JSON.readTree(body);
        events.forEach(e -> {
            List<String> fields = new ArrayList<>();
            e.fieldNames().forEachRemaining(fields::add);
            assertEquals(List.of("eventId", "endTime"), fields,
                "an event carried unexpected fields: " + e);
        });
        for (String forbidden : new String[]{"cells", "solution", "board", "playerId"}) {
            assertFalse(body.contains(forbidden), "the events payload leaked \"" + forbidden + "\": " + body);
        }
    }

    // ---------------- LeaderboardController ----------------

    /** Reproduction of the 500-instead-of-400 bug on the leaderboard's paging parameter. */
    @Test
    @DisplayName("an out-of-range leaderboard limit is 400, not 500")
    void leaderboardRejectsAnOutOfRangeLimitAsABadRequest() throws Exception {
        for (String limit : new String[]{"0", "-1", "-5", "101", "2147483647"}) {
            mockMvc.perform(get("/api/leaderboard?limit=" + limit).with(as(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Parameter"));
        }
    }

    @Test
    void aNonNumericLeaderboardLimitIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/leaderboard?limit=abc").with(as(me)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void theLeaderboardHonoursTheRequestedLimit() throws Exception {
        // Three more accounts on top of the two from setUp guarantee the table can supply
        // more rows than we ask for, so a limit that is ignored is visible.
        freshPlayer();
        freshPlayer();
        freshPlayer();

        JsonNode entries = JSON.readTree(mockMvc.perform(get("/api/leaderboard?limit=2").with(as(me)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertEquals(2, entries.size(), "limit=2 returned " + entries.size() + " entries");
        assertEquals(1, entries.get(0).get("rank").asInt());
        assertEquals(2, entries.get(1).get("rank").asInt());
    }

    @Test
    void theLeaderboardDefaultsToFiveEntries() throws Exception {
        // The declared default on the handler parameter. Six accounts exist by now, so a
        // changed default is observable rather than masked by an empty table.
        freshPlayer();
        freshPlayer();
        freshPlayer();
        freshPlayer();

        JsonNode entries = JSON.readTree(mockMvc.perform(get("/api/leaderboard").with(as(me)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(5, entries.size(), "the documented default page size of 5 changed");
    }

    @Test
    void leaderboardEntriesCarryOnlyThePublicProjection() throws Exception {
        String body = mockMvc.perform(get("/api/leaderboard?limit=5").with(as(me)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode entries = JSON.readTree(body);
        assertTrue(entries.size() > 0, "the leaderboard was empty; the fixture accounts should rank");
        entries.forEach(e -> {
            List<String> fields = new ArrayList<>();
            e.fieldNames().forEachRemaining(fields::add);
            assertEquals(List.of("rank", "username", "sortValue", "tier", "cosmicDrip", "hypeMeter", "duelWins"),
                fields, "a leaderboard entry carried unexpected fields: " + e);
        });
        // The service maps from full User rows, so a projection change could drag these in.
        for (String forbidden : new String[]{"password", "Hash", "lastLoginIp", "gems", "email"}) {
            assertFalse(body.contains(forbidden), "the leaderboard leaked \"" + forbidden + "\": " + body);
        }
    }

    @Test
    void leaderboardRejectsAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/leaderboard")).andExpect(status().isUnauthorized());
        // ...and does so BEFORE validating parameters, so an anonymous caller cannot use
        // the endpoint as an oracle either.
        mockMvc.perform(get("/api/leaderboard?limit=0")).andExpect(status().isUnauthorized());
    }
}
