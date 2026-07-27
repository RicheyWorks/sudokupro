package com.xai.sudokupro.service.duel;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Elo conservation at the rating floor, against the REAL {@code DuelService}.
 *
 * <p>{@link User#setDuelRating(int)} clamps with {@code Math.max(0, ...)}. The winner was
 * credited the full delta while a near-zero loser surrendered only what they had, so the
 * exchange stopped being zero-sum and rating was created from nothing — worked example at
 * K=32: winner 195 vs loser 5 gives delta 8, the loser drops 5, and three points appear.
 * From there every duel against a floored account is +8/-0.
 *
 * <p>This class previously reimplemented the two-line arithmetic locally, on the reasoning
 * that it avoided standing up the service graph. A mutation audit showed the cost:
 * deleting the real clamp from {@code DuelService.recordResult} left every test here green.
 * A test that reimplements the code it covers only proves the copy is self-consistent. It
 * now invokes the production method.
 */
class DuelRatingFloorTest {

    private final Map<String, User> users = new HashMap<>();

    private DuelService serviceWithUsers(String... names) {
        UserRepository repo = mock(UserRepository.class);
        lenient().when(repo.findByUsername(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(users.get(inv.<String>getArgument(0))));
        lenient().when(repo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });
        lenient().when(repo.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });
        for (String n : names) users.put(n, new User(null, n));

        var downRedis = mock(org.springframework.data.redis.core.StringRedisTemplate.class,
            inv -> { throw new org.springframework.data.redis.RedisConnectionFailureException("down (test)"); });

        return new DuelService(
            mock(com.xai.sudokupro.service.GameService.class),
            new com.xai.sudokupro.model.SudokuGenerator(
                new com.xai.sudokupro.util.SecureRandomGenerator(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry())),
            new DuelStateStore(downRedis),
            repo,
            mock(com.xai.sudokupro.service.NotificationService.class),
            mock(com.xai.sudokupro.service.AnalyticsService.class));
    }

    /** Invokes the production settlement directly — it is the unit under test. */
    private static void settle(DuelService service, String winner, String loser) throws Exception {
        Method m = DuelService.class.getDeclaredMethod("recordResult", String.class, String.class);
        m.setAccessible(true);
        m.invoke(service, winner, loser);
    }

    private int rating(String who) {
        return users.get(who).getDuelRating();
    }

    /** The defect, stated as a property: a duel must move rating, never create it. */
    @Test
    void aDuelNeverCreatesRating() throws Exception {
        int[][] cases = {{195, 5}, {203, 0}, {1000, 1000}, {1600, 400}, {100, 1}, {50, 0}};
        for (int[] c : cases) {
            users.clear();
            DuelService service = serviceWithUsers("w", "l");
            users.get("w").setDuelRating(c[0]);
            users.get("l").setDuelRating(c[1]);
            int before = c[0] + c[1];

            settle(service, "w", "l");

            assertEquals(before, rating("w") + rating("l"),
                "rating must be conserved for winner=" + c[0] + " loser=" + c[1]
                    + " but went " + before + " -> " + (rating("w") + rating("l")));
            assertTrue(rating("l") >= 0, "the loser must never go negative");
        }
    }

    /**
     * A player parked at zero was a faucet: every duel against them paid the winner in
     * full for nothing, up to roughly 720 points before the transfer saturated.
     */
    @Test
    void aFlooredOpponentIsNotAFaucet() throws Exception {
        DuelService service = serviceWithUsers("grinder", "floored");
        users.get("grinder").setDuelRating(200);
        users.get("floored").setDuelRating(0);

        for (int i = 0; i < 50; i++) settle(service, "grinder", "floored");

        assertEquals(200, rating("grinder"),
            "beating a zero-rated opponent fifty times must transfer nothing, got "
                + rating("grinder"));
        assertEquals(0, rating("floored"));
    }

    /** Ordinary duels between healthy ratings must be untouched by the clamp. */
    @Test
    void anEvenMatchStillTransfersTheFullElo() throws Exception {
        DuelService service = serviceWithUsers("a", "b");
        users.get("a").setDuelRating(1000);
        users.get("b").setDuelRating(1000);

        settle(service, "a", "b");

        assertEquals(1016, rating("a"), "an even match at K=32 transfers 16");
        assertEquals(984, rating("b"));
    }

    /** Win/loss records must be updated alongside the rating. */
    @Test
    void theWinLossRecordIsUpdated() throws Exception {
        DuelService service = serviceWithUsers("winner", "loser");

        settle(service, "winner", "loser");

        assertEquals(1, users.get("winner").getDuelWins(), "the winner's win count");
        assertEquals(1, users.get("loser").getDuelLosses(), "the loser's loss count");
        assertEquals(0, users.get("winner").getDuelLosses());
        assertEquals(0, users.get("loser").getDuelWins());
    }
}
