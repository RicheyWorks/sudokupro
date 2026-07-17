package com.xai.sudokupro.service.social;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.service.economy.EconomyService;
import com.xai.sudokupro.websocket.GameSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FriendServiceTest {

    @Mock private GameSessionRegistry sessions;
    @Mock private NotificationService notificationService;

    private final Map<String, User> byName = new ConcurrentHashMap<>();
    private final Map<Long, User> byId = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private FriendService service;

    @BeforeEach
    void setUp() {
        UserRepository repo = mock(UserRepository.class);
        lenient().when(repo.findByUsername(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(byName.get(inv.<String>getArgument(0))));
        lenient().when(repo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(ids.getAndIncrement());
            byName.put(u.getUsername(), u);
            byId.put(u.getId(), u);
            return u;
        });
        lenient().when(repo.findAllById(any())).thenAnswer(inv -> {
            List<User> out = new ArrayList<>();
            for (Long id : inv.<Iterable<Long>>getArgument(0)) {
                User u = byId.get(id);
                if (u != null) out.add(u);
            }
            return out;
        });
        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        service = new FriendService(new EconomyService(repo, 5, 15, 5), repo,
            sessions, notificationService, downRedis);
    }

    @Test
    void requestAcceptFormsAMutualFriendshipWithPresence() {
        service.request("richmond", "ada");
        assertTrue(service.pendingFor("ada").contains("richmond"));

        service.accept("ada", "richmond");
        when(sessions.isOnline("ada")).thenReturn(true);

        var richmondsFriends = service.friendsOf("richmond");
        assertEquals(1, richmondsFriends.size());
        assertEquals("ada", richmondsFriends.get(0).playerId());
        assertTrue(richmondsFriends.get(0).online());
        assertEquals("richmond", service.friendsOf("ada").get(0).playerId(),
            "friendship must be mutual");
        assertTrue(service.pendingFor("ada").isEmpty(), "accepted request leaves the queue");
        verify(notificationService).sendTypedNotification(eq("richmond"), eq("FRIEND"), contains("accepted"));
    }

    @Test
    void acceptingANonexistentRequestFails() {
        assertThrows(IllegalArgumentException.class, () -> service.accept("ada", "stranger"));
    }

    @Test
    void selfFriendshipIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.request("richmond", "richmond"));
    }

    @Test
    void removeSeversBothDirections() {
        service.request("richmond", "ada");
        service.accept("ada", "richmond");

        service.remove("richmond", "ada");

        assertTrue(service.friendsOf("richmond").isEmpty());
        assertTrue(service.friendsOf("ada").isEmpty());
    }
}
