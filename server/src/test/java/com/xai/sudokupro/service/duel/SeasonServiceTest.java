package com.xai.sudokupro.service.duel;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeasonServiceTest {

    private static final Clock Q3 = Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC);
    private static final Clock Q4 = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);

    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;

    /**
     * A map-backed Redis rather than one that throws on every call. The season markers
     * ARE the state under test now — a rollover is only legitimate when the previous
     * season's marker exists — so the suite has to be able to seed and observe them.
     * A throwing mock exercised only the in-memory fallback, whose map is private, and
     * that is precisely why the fresh-install case below could not be expressed before.
     */
    private final Map<String, String> redisKeys = new ConcurrentHashMap<>();
    private StringRedisTemplate redis;
    private SeasonService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        lenient().when(redis.opsForValue()).thenReturn(ops);
        lenient().when(ops.setIfAbsent(anyString(), anyString()))
            .thenAnswer(inv -> redisKeys.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        lenient().when(redis.hasKey(anyString()))
            .thenAnswer(inv -> redisKeys.containsKey(inv.<String>getArgument(0)));

        service = new SeasonService(userRepository, notificationService, redis, Q3);
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Records that this install was running during {@code season}. */
    private void observed(String season) {
        redisKeys.put("sudokupro:season:rolled:" + season, "1");
    }

    @Test
    void seasonIdentityAndEndDate() {
        assertEquals("2026-Q3", service.seasonId());
        assertEquals("2026-10-01", service.seasonEnds().toString());
    }

    /**
     * Regression: the podium badge must carry the season that ENDED.
     *
     * <p>Rollover labelled the badge with {@code seasonId()} — the season now STARTING —
     * while scoring the outgoing ladder. With the clock in Q3 the Q2 winners were crowned
     * "SeasonChampion-2026-Q3", so nobody ever held a badge for the season they actually
     * won, and the real Q3 champion could never receive that badge because the key was
     * already taken.
     *
     * <p>Also: the soft reset used to iterate the same top-100 page as the podium, leaving
     * everyone from rank 101 down un-reset. It is now one bulk statement over every rated
     * player.
     */
    @Test
    void rolloverCrownsLastSeasonsPodiumAndResetsEveryRatedPlayerExactlyOnce() {
        User first = rated("champ", 1400);
        User second = rated("runner", 1200);
        User third = rated("third", 1100);
        when(userRepository.findDuelLadder(any())).thenReturn(List.of(first, second, third));
        when(userRepository.softResetDuelRatings()).thenReturn(150);
        observed("2026-Q2"); // this install ran during Q2, so Q2→Q3 is a real crossing

        service.current();  // triggers the rollover
        service.current();  // second call must be a no-op

        // Clock is fixed in Q3, so the season that just ended is Q2.
        assertEquals("2026-Q2", service.previousSeasonId());
        assertTrue(first.getAchievements().get("SeasonChampion-2026-Q2"));
        assertTrue(third.getAchievements().get("SeasonChampion-2026-Q2"));
        assertNull(first.getAchievements().get("SeasonChampion-2026-Q3"),
            "the badge must not be stamped with the season that is only just starting");

        // Every rated player is reset by one statement, not just the podium page.
        verify(userRepository, times(1)).softResetDuelRatings();
        verify(userRepository, times(1)).findDuelLadder(any());
        verify(notificationService, times(3))
            .sendTypedNotification(anyString(), eq("SEASON"), anyString());
    }

    @Test
    void emptyLadderRollsOverQuietly() {
        observed("2026-Q2");
        when(userRepository.findDuelLadder(any())).thenReturn(List.of());
        assertEquals("2026-Q3", service.current().get("seasonId"));
        verify(notificationService, never()).sendTypedNotification(anyString(), anyString(), anyString());
    }

    /**
     * The defect: <b>a mid-quarter first call destroyed the ladder.</b>
     *
     * <p>{@code rolloverIfDue} claimed the marker for the season now starting and then,
     * on any non-empty ladder, crowned a podium and soft-reset EVERY rated player's
     * rating — in the middle of a quarter that had not ended. The only escape was an
     * empty ladder, which stops being true the moment anyone duels. So the first
     * {@code /api/season} call on a fresh install, or on a restored database, silently
     * compressed every rating it was only supposed to report on.
     *
     * <p>The method's own javadoc claimed "before any season has ever been marked, it
     * simply marks the current one", and no code implemented that sentence. A rollover
     * now requires evidence that a season actually ended while this install was
     * watching: the previous season's marker.
     */
    @Test
    void aFirstSightingMidQuarterCrownsNobodyAndResetsNothing() {
        lenient().when(userRepository.findDuelLadder(any()))
            .thenReturn(List.of(rated("champ", 1400), rated("runner", 1200)));
        lenient().when(userRepository.softResetDuelRatings()).thenReturn(150);

        // No 2026-Q2 marker: this process has never seen that season.
        assertEquals("2026-Q3", service.current().get("seasonId"));

        verify(userRepository, never()).softResetDuelRatings();
        verify(userRepository, never()).save(any(User.class));
        verify(notificationService, never()).sendTypedNotification(anyString(), anyString(), anyString());
    }

    /**
     * And the marker that first sighting claims is what makes the NEXT crossing work —
     * a fresh install must not end up permanently unable to roll over.
     */
    @Test
    void theSeasonAfterAFirstSightingRollsOverNormally() {
        when(userRepository.findDuelLadder(any())).thenReturn(List.of(rated("champ", 1400)));
        when(userRepository.softResetDuelRatings()).thenReturn(7);

        service.current(); // first sighting in Q3: marks Q3, changes nothing
        verify(userRepository, never()).softResetDuelRatings();

        SeasonService inQ4 = new SeasonService(userRepository, notificationService, redis, Q4);
        inQ4.current();    // Q4 arrives, Q3 was observed — a real crossing

        assertEquals("2026-Q4", inQ4.seasonId());
        verify(userRepository, times(1)).softResetDuelRatings();
        verify(notificationService, times(1))
            .sendTypedNotification(anyString(), eq("SEASON"), contains("2026-Q3"));
    }

    private static User rated(String name, int rating) {
        User u = new User(null, name);
        u.setDuelRating(rating);
        u.setDuelWins(1);
        return u;
    }
}
