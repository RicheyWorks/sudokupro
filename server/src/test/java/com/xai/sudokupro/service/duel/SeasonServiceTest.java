package com.xai.sudokupro.service.duel;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeasonServiceTest {

    private static final Clock Q3 = Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC);

    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;

    private SeasonService service;

    @BeforeEach
    void setUp() {
        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        service = new SeasonService(userRepository, notificationService, downRedis, Q3);
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
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
        when(userRepository.findDuelLadder(any())).thenReturn(List.of());
        assertEquals("2026-Q3", service.current().get("seasonId"));
        verify(notificationService, never()).sendTypedNotification(anyString(), anyString(), anyString());
    }

    private static User rated(String name, int rating) {
        User u = new User(null, name);
        u.setDuelRating(rating);
        u.setDuelWins(1);
        return u;
    }
}
