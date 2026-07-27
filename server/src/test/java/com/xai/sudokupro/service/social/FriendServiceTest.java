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
    private UserRepository repoSpy;

    @BeforeEach
    void setUp() {
        UserRepository repo = mock(UserRepository.class);
        this.repoSpy = repo;
        lenient().when(repo.findByUsername(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(byName.get(inv.<String>getArgument(0))));
        lenient().when(repo.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(ids.getAndIncrement());
            byName.put(u.getUsername(), u);
            byId.put(u.getId(), u);
            return u;
        });
        lenient().when(repo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(ids.getAndIncrement());
            byName.put(u.getUsername(), u);
            byId.put(u.getId(), u);
            return u;
        });
        // The friend graph is now written edge-at-a-time through the repository rather than
        // by rewriting User.friends and calling save() — see UserRepository.linkFriend. The
        // fake below applies the same semantics to the in-memory users so these tests keep
        // exercising the real service logic.
        lenient().when(repo.linkFriend(any(), any())).thenAnswer(inv -> {
            User u = byId.get(inv.<Long>getArgument(0));
            Long friendId = inv.getArgument(1);
            if (u == null || u.getFriends().contains(friendId)) return 0;
            u.addFriend(friendId);
            return 1;
        });
        lenient().when(repo.unlinkFriend(any(), any())).thenAnswer(inv -> {
            User u = byId.get(inv.<Long>getArgument(0));
            Long friendId = inv.getArgument(1);
            if (u == null || !u.getFriends().contains(friendId)) return 0;
            u.removeFriend(friendId);
            return 1;
        });
        lenient().when(repo.countFriendEdge(any(), any())).thenAnswer(inv -> {
            User u = byId.get(inv.<Long>getArgument(0));
            return (u != null && u.getFriends().contains(inv.<Long>getArgument(1))) ? 1L : 0L;
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

    /** request() now requires a real target account, so seed one. */
    private void givenPlayerExists(String name) {
        User u = new User(null, name);
        u.setId(ids.getAndIncrement());
        byName.put(name, u);
        byId.put(u.getId(), u);
    }

    @Test
    void requestAcceptFormsAMutualFriendshipWithPresence() {
        givenPlayerExists("ada");
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

    /**
     * Regression: a friend request to a name that does not exist returned HTTP 200 and
     * wrote a pending entry (with a 14-day Redis key). Found by fuzzing — a request to an
     * emoji username succeeded. That is unbounded attacker-controlled key growth, and it
     * let a caller populate arbitrary names.
     */
    @Test
    void requestToANonexistentPlayerIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> service.request("richmond", "ghost-who-never-registered"));
        assertTrue(service.pendingFor("ghost-who-never-registered").isEmpty(),
            "a rejected request must not leave state behind");
    }

    @Test
    void selfFriendshipIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.request("richmond", "richmond"));
    }

    @Test
    void decliningWithoutAnyLocalStateDoesNotCrash() {
        // Regression: the local fallback used Set.of().remove(), whose
        // UnsupportedOperationException fired whenever Redis was down and the
        // player had no pending entry.
        assertDoesNotThrow(() -> service.decline("ada", "stranger"));
    }

    @Test
    void removeSeversBothDirections() {
        givenPlayerExists("ada");
        service.request("richmond", "ada");
        service.accept("ada", "richmond");

        service.remove("richmond", "ada");

        assertTrue(service.friendsOf("richmond").isEmpty());
        assertTrue(service.friendsOf("ada").isEmpty());
    }

    /**
     * Requesting someone who is already your friend must be refused.
     *
     * <p>Nothing checked, so the friends screen's "Add friend" could be pointed at an
     * existing friend indefinitely: each call re-notified them and re-armed a pending
     * request that, once accepted, re-ran the edge writes. A friendship is not a thing you
     * can be asked for twice.
     */
    @Test
    void requestingAnExistingFriendIsRejected() {
        givenPlayerExists("ada");
        service.request("richmond", "ada");
        service.accept("ada", "richmond");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.request("richmond", "ada"));
        assertTrue(e.getMessage().contains("already your friend"), e.getMessage());
    }

    /**
     * A repeated request must not generate a second notification.
     *
     * <p>The pending set deduplicated the entry — but the notify() call sat outside that
     * check and fired unconditionally, so a loop over the same endpoint produced an
     * unlimited stream of "X wants to be your friend" in the target's app. The push channel
     * has a five-minute cooldown; the WebSocket one, which is the notification the player
     * actually sees, has none.
     */
    @Test
    void repeatedFriendRequestsDoNotSpamTheTarget() {
        givenPlayerExists("ada");

        for (int i = 0; i < 25; i++) service.request("richmond", "ada");

        verify(notificationService, times(1))
            .sendTypedNotification(eq("ada"), eq("FRIEND"), contains("wants to be your friend"));
        assertEquals(Set.of("richmond"), service.pendingFor("ada"),
            "the inbox holds one entry per requester, however many times they ask");
    }

    /**
     * The pending inbox has a ceiling. Without one, N accounts could each leave an entry
     * (each carrying a 14-day Redis key) with nothing bounding the set.
     */
    @Test
    void aPendingInboxIsBounded() {
        givenPlayerExists("ada");
        for (int i = 0; i < FriendService.MAX_PENDING_PER_PLAYER; i++) {
            service.request("spammer-" + i, "ada");
        }
        assertEquals(FriendService.MAX_PENDING_PER_PLAYER, service.pendingFor("ada").size());

        assertThrows(IllegalStateException.class, () -> service.request("one-too-many", "ada"),
            "past the cap a new requester must be refused, not silently dropped");
        assertFalse(service.pendingFor("ada").contains("one-too-many"));
    }

    /**
     * Accepting must write both edges through the atomic per-edge repository calls rather
     * than by rewriting each user's whole friend collection.
     *
     * <p>{@code User.friends} is an {@code @ElementCollection}: Hibernate persists a change
     * to one by deleting every row for that user and reinserting the set it holds in
     * memory. Two accepts touching the same person concurrently therefore each write a full
     * snapshot and the later one erases the earlier — and because accept writes two
     * independent directions, losing one leaves a permanent ONE-WAY friendship with no
     * pending request left to retry from. This pins the shape of the fix; the race itself
     * is a database-level property the unit test cannot reproduce.
     */
    @Test
    void acceptWritesEachFriendEdgeAtomicallyRatherThanRewritingTheCollection() {
        givenPlayerExists("ada");
        service.request("richmond", "ada");
        Long adaId = byName.get("ada").getId();

        service.accept("ada", "richmond");
        Long richmondId = byName.get("richmond").getId();

        verify(repoSpy).linkFriend(adaId, richmondId);
        verify(repoSpy).linkFriend(richmondId, adaId);
        verify(repoSpy, never()).save(argThat(u -> "ada".equals(u.getUsername())));
    }
}
