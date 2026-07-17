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

    @Test
    void rolloverCrownsPodiumAndSoftResetsRatingsExactlyOnce() {
        User first = rated("champ", 1400);
        User second = rated("runner", 1200);
        User third = rated("third", 1100);
        User fourth = rated("fourth", 900);
        when(userRepository.findDuelLadder(any())).thenReturn(List.of(first, second, third, fourth));

        service.current();  // triggers the rollover
        service.current();  // second call must be a no-op

        assertTrue(first.getAchievements().get("SeasonChampion-2026-Q3"));
        assertTrue(third.getAchievements().get("SeasonChampion-2026-Q3"));
        assertNull(fourth.getAchievements().get("SeasonChampion-2026-Q3"));
        assertEquals((1400 + 1000) / 2, first.getDuelRating(), "soft reset toward 1000");
        assertEquals((900 + 1000) / 2, fourth.getDuelRating());
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
