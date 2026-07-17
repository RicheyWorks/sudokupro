package com.xai.sudokupro.service.social;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.service.economy.EconomyService;
import com.xai.sudokupro.websocket.GameSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Friends & presence: request → accept → mutual entries in the (previously
 * dormant) {@code User.friends} id set. Pending requests live in Redis with the
 * usual local-map degrade; presence is the gameplay WebSocket registry.
 */
@Service
public class FriendService {

    private static final Logger logger = LoggerFactory.getLogger(FriendService.class);
    private static final String PENDING_KEY = "sudokupro:friends:pending:"; // + playerId → set of requesters
    private static final Duration PENDING_TTL = Duration.ofDays(14);

    public record FriendView(String playerId, boolean online) {}

    private final EconomyService economyService; // wallet provisioning = user rows
    private final UserRepository userRepository;
    private final GameSessionRegistry sessions;
    private final NotificationService notificationService;
    private final StringRedisTemplate redis;
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);
    private final Map<String, Set<String>> localPending = new ConcurrentHashMap<>();

    public FriendService(EconomyService economyService, UserRepository userRepository,
                         GameSessionRegistry sessions, NotificationService notificationService,
                         StringRedisTemplate redis) {
        this.economyService = economyService;
        this.userRepository = userRepository;
        this.sessions = sessions;
        this.notificationService = notificationService;
        this.redis = redis;
    }

    public void request(String from, String to) {
        if (from.equals(to)) throw new IllegalArgumentException("You cannot befriend yourself");
        try {
            redis.opsForSet().add(PENDING_KEY + to, from);
            redis.expire(PENDING_KEY + to, PENDING_TTL);
        } catch (Exception e) {
            degraded(e);
            localPending.computeIfAbsent(to, x -> ConcurrentHashMap.newKeySet()).add(from);
        }
        notify(to, from + " wants to be your friend — accept in the Friends menu.");
        logger.info("Friend request {} -> {}", from, to);
    }

    @Transactional
    public void accept(String me, String requester) {
        if (!pendingFor(me).contains(requester)) {
            throw new IllegalArgumentException("No pending request from " + requester);
        }
        removePending(me, requester);
        User a = economyService.walletFor(me);
        User b = economyService.walletFor(requester);
        a.addFriend(b.getId());
        b.addFriend(a.getId());
        userRepository.save(a);
        userRepository.save(b);
        notify(requester, me + " accepted your friend request!");
        logger.info("Friendship formed: {} <-> {}", me, requester);
    }

    public void decline(String me, String requester) {
        removePending(me, requester);
    }

    @Transactional
    public void remove(String me, String exFriend) {
        User a = economyService.walletFor(me);
        User b = economyService.walletFor(exFriend);
        Set<Long> af = a.getFriends(); af.remove(b.getId()); a.setFriends(af);
        Set<Long> bf = b.getFriends(); bf.remove(a.getId()); b.setFriends(bf);
        userRepository.save(a);
        userRepository.save(b);
    }

    /** The caller's friends with live presence flags. */
    public List<FriendView> friendsOf(String me) {
        User user = economyService.walletFor(me);
        List<FriendView> out = new ArrayList<>();
        for (User friend : userRepository.findAllById(user.getFriends())) {
            out.add(new FriendView(friend.getUsername(), sessions.isOnline(friend.getUsername())));
        }
        out.sort(Comparator.comparing(FriendView::online).reversed()
            .thenComparing(FriendView::playerId));
        return out;
    }

    public Set<String> pendingFor(String me) {
        try {
            Set<String> members = redis.opsForSet().members(PENDING_KEY + me);
            return members != null ? members : Set.of();
        } catch (Exception e) {
            degraded(e);
            return Set.copyOf(localPending.getOrDefault(me, Set.of()));
        }
    }

    private void removePending(String me, String requester) {
        try {
            redis.opsForSet().remove(PENDING_KEY + me, requester);
        } catch (Exception e) {
            degraded(e);
        }
        // NOT getOrDefault(me, Set.of()).remove(...): Set.of() is immutable and
        // its remove() throws UnsupportedOperationException unconditionally.
        Set<String> pending = localPending.get(me);
        if (pending != null) pending.remove(requester);
    }

    private void notify(String playerId, String message) {
        try {
            notificationService.sendTypedNotification(playerId, "FRIEND", message);
        } catch (Exception e) {
            logger.debug("Friend notification failed: {}", e.getMessage());
        }
    }

    private void degraded(Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            logger.warn("FriendService: Redis unavailable — pending requests in-memory only. Cause: {}",
                e.getMessage());
        }
    }
}
