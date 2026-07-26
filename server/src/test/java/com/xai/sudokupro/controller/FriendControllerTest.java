package com.xai.sudokupro.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FriendController}.
 *
 * <h2>Defect classes this protects against</h2>
 * <ol>
 *   <li><b>Acting on a friendship you are not part of.</b> Every handler derives one side
 *       of the edge from the Principal and the other from the path variable, so the caller
 *       can only ever touch edges that include itself. The tests that matter are the
 *       negative ones: a third party must not be able to accept somebody else's pending
 *       request ({@link #aThirdPartyCannotAcceptSomebodyElsesPendingRequest()}) nor read
 *       somebody else's inbox ({@link #pendingShowsOnlyTheCallersOwnIncomingRequests()}).</li>
 *   <li><b>Principal/target confusion.</b> If the two arguments of
 *       {@code friendService.request(me, them)} were ever transposed, the feature would
 *       still "work" superficially — a request would be written — but against the wrong
 *       inbox. Several tests below assert the DIRECTION of the edge, not merely that
 *       something happened.</li>
 *   <li><b>Self-friendship and phantom targets.</b> Befriending yourself, and writing a
 *       14-day pending entry for a username that does not exist (attacker-controlled key
 *       growth), are both refused with 400.</li>
 *   <li><b>Replayable accepts.</b> The pending entry must be consumed, so the same request
 *       cannot be accepted twice.</li>
 *   <li><b>Unauthenticated / CSRF-less access.</b></li>
 * </ol>
 *
 * <p>Every test mints its own three accounts in {@link #setUp()}; the friend graph and the
 * (Redis-less, in-memory) pending map are process-wide singletons in this test context, so
 * reusing names across methods would make one method's leftovers decide another's result.
 */
class FriendControllerTest extends ControllerSliceTestBase {

    private String alice;
    private String bob;
    private String carol;

    @BeforeEach
    void setUp() throws Exception {
        alice = freshPlayer();
        bob = freshPlayer();
        carol = freshPlayer();
    }

    private void request(String from, String to) throws Exception {
        mockMvc.perform(post("/api/friends/request/" + to).with(as(from)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("requested"));
    }

    private void accept(String me, String requester) throws Exception {
        mockMvc.perform(post("/api/friends/accept/" + requester).with(as(me)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("friends"));
    }

    // ---------------- happy path ----------------

    @Test
    @DisplayName("request -> accept produces a friendship visible from BOTH sides")
    void requestThenAcceptCreatesAMutualFriendship() throws Exception {
        request(alice, bob);

        // Direction check: the request landed in BOB's inbox, not Alice's.
        mockMvc.perform(get("/api/friends/pending").with(as(bob)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem(alice)));
        mockMvc.perform(get("/api/friends/pending").with(as(alice)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(bob))));

        accept(bob, alice);

        mockMvc.perform(get("/api/friends").with(as(alice)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.playerId=='" + bob + "')]").exists());
        mockMvc.perform(get("/api/friends").with(as(bob)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.playerId=='" + alice + "')]").exists());
    }

    @Test
    void removingAFriendClearsTheEdgeInBothDirections() throws Exception {
        request(alice, bob);
        accept(bob, alice);

        mockMvc.perform(delete("/api/friends/" + bob).with(as(alice)).with(csrf()))
            .andExpect(status().isNoContent());

        // Both sides, because a one-sided delete leaves a ghost friend on the other account.
        mockMvc.perform(get("/api/friends").with(as(alice)))
            .andExpect(jsonPath("$[?(@.playerId=='" + bob + "')]").doesNotExist());
        mockMvc.perform(get("/api/friends").with(as(bob)))
            .andExpect(jsonPath("$[?(@.playerId=='" + alice + "')]").doesNotExist());
    }

    @Test
    void decliningConsumesThePendingRequestWithoutCreatingAFriendship() throws Exception {
        request(alice, bob);

        mockMvc.perform(post("/api/friends/decline/" + alice).with(as(bob)).with(csrf()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/friends/pending").with(as(bob)))
            .andExpect(jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(alice))));
        mockMvc.perform(get("/api/friends").with(as(bob)))
            .andExpect(jsonPath("$[?(@.playerId=='" + alice + "')]").doesNotExist());
        // And the declined request can no longer be accepted.
        mockMvc.perform(post("/api/friends/accept/" + alice).with(as(bob)).with(csrf()))
            .andExpect(status().isNotFound());
    }

    // ---------------- wrong-user / third-party ----------------

    /**
     * Carol must not be able to convert Alice's request-to-Bob into a friendship with
     * herself. The service consults {@code pendingFor(me)} — Carol's own inbox — so the
     * request is invisible to her and comes back 404. The second half of the assertion is
     * the important one: no edge may have been created as a side effect.
     */
    @Test
    void aThirdPartyCannotAcceptSomebodyElsesPendingRequest() throws Exception {
        request(alice, bob);

        mockMvc.perform(post("/api/friends/accept/" + alice).with(as(carol)).with(csrf()))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/friends").with(as(carol)))
            .andExpect(jsonPath("$[?(@.playerId=='" + alice + "')]").doesNotExist());
        mockMvc.perform(get("/api/friends").with(as(alice)))
            .andExpect(jsonPath("$[?(@.playerId=='" + carol + "')]").doesNotExist());
        // Bob's pending request survives Carol's attempt to consume it — and it is in BOB's
        // inbox, not the sender's. Both halves are asserted: a "present in the recipient's
        // inbox" check alone can be satisfied by unrelated state in this shared-context
        // suite, whereas "absent from the sender's inbox" pins the direction on its own.
        mockMvc.perform(get("/api/friends/pending").with(as(bob)))
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem(alice)));
        mockMvc.perform(get("/api/friends/pending").with(as(alice)))
            .andExpect(jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(bob))));
    }

    @Test
    void pendingShowsOnlyTheCallersOwnIncomingRequests() throws Exception {
        request(alice, carol);

        mockMvc.perform(get("/api/friends/pending").with(as(carol)))
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasItem(alice)));
        // The sender must NOT end up holding a request from the person she wrote to; this is
        // the half that transposing the two arguments of friendService.request() breaks.
        mockMvc.perform(get("/api/friends/pending").with(as(alice)))
            .andExpect(jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(carol))));
        // Bob is uninvolved and must see nothing of it.
        mockMvc.perform(get("/api/friends/pending").with(as(bob)))
            .andExpect(jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(alice))));
    }

    @Test
    void theFriendListIsTheCallersOwn() throws Exception {
        request(alice, bob);
        accept(bob, alice);

        // Carol is friends with nobody; she must not inherit Alice's list.
        mockMvc.perform(get("/api/friends").with(as(carol)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.playerId=='" + bob + "')]").doesNotExist())
            .andExpect(jsonPath("$[?(@.playerId=='" + alice + "')]").doesNotExist());
    }

    /**
     * A third party's un-friend call must not dissolve an edge it is not part of, and no
     * smuggled parameter may redirect whose edge is being removed.
     *
     * <p>The extra query parameters are the shape of the recurring bug in this codebase: a
     * caller-controlled {@code playerId} that is not cross-checked against the Principal.
     */
    @Test
    void aThirdPartyCannotDissolveAFriendshipItIsNotPartOf() throws Exception {
        request(alice, bob);
        accept(bob, alice);

        for (String smuggle : new String[]{"", "?playerId=" + alice, "?as=" + alice, "?me=" + alice}) {
            mockMvc.perform(delete("/api/friends/" + bob + smuggle).with(as(carol)).with(csrf()))
                .andExpect(status().isNoContent());
        }

        mockMvc.perform(get("/api/friends").with(as(alice)))
            .andExpect(jsonPath("$[?(@.playerId=='" + bob + "')]").exists());
        mockMvc.perform(get("/api/friends").with(as(bob)))
            .andExpect(jsonPath("$[?(@.playerId=='" + alice + "')]").exists());
    }

    // ---------------- malformed input ----------------

    @Test
    void befriendingYourselfIsRejected() throws Exception {
        mockMvc.perform(post("/api/friends/request/" + alice).with(as(alice)).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("You cannot befriend yourself"));

        mockMvc.perform(get("/api/friends").with(as(alice)))
            .andExpect(jsonPath("$[?(@.playerId=='" + alice + "')]").doesNotExist());
    }

    @Test
    void aRequestToANonExistentPlayerIsRejectedAndWritesNothing() throws Exception {
        String ghost = "no-such-player-" + java.util.UUID.randomUUID();

        mockMvc.perform(post("/api/friends/request/" + ghost).with(as(alice)).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("No such player: " + ghost));

        // The refusal must also not have provisioned a users row for the ghost name —
        // "unbounded attacker-controlled key growth" is the reason the check exists.
        org.junit.jupiter.api.Assertions.assertTrue(userRepository.findByUsername(ghost).isEmpty(),
            "a rejected friend request still minted a users row for " + ghost);
    }

    @Test
    void acceptingARequestThatWasNeverSentIs404() throws Exception {
        mockMvc.perform(post("/api/friends/accept/" + bob).with(as(alice)).with(csrf()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("No pending request from " + bob));
    }

    /**
     * The pending entry must be CONSUMED by an accept. If it survived, the same request
     * could be replayed indefinitely — and, worse, replayed after the friendship had been
     * deliberately removed, silently re-creating it.
     */
    @Test
    void aPendingRequestCannotBeAcceptedTwice() throws Exception {
        request(alice, bob);
        accept(bob, alice);

        mockMvc.perform(post("/api/friends/accept/" + alice).with(as(bob)).with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    void unFriendingSomebodyWhoDoesNotExistIsAHarmlessNoOp() throws Exception {
        String ghost = "no-such-player-" + java.util.UUID.randomUUID();

        mockMvc.perform(delete("/api/friends/" + ghost).with(as(alice)).with(csrf()))
            .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertTrue(userRepository.findByUsername(ghost).isEmpty(),
            "DELETE /api/friends/{unknown} minted a users row — the endpoint is a row factory again");
    }

    // ---------------- authentication / CSRF ----------------

    @Test
    void everyFriendEndpointRejectsAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/friends")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/friends/pending")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/friends/request/" + bob).with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/friends/accept/" + bob).with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/friends/decline/" + bob).with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/friends/" + bob).with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void mutatingFriendCallsWithoutACsrfTokenAreRejected() throws Exception {
        mockMvc.perform(post("/api/friends/request/" + bob).with(as(alice)))
            .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/friends/" + bob).with(as(alice)))
            .andExpect(status().isForbidden());

        // ...and nothing happened.
        mockMvc.perform(get("/api/friends/pending").with(as(bob)))
            .andExpect(jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(alice))));
    }
}
