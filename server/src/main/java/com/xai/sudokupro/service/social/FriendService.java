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
    /** Ceiling on one player's pending-request inbox; see {@link #request(String, String)}. */
    static final int MAX_PENDING_PER_PLAYER = 200;

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

    /**
     * Records a pending friend request from {@code from} to {@code to}.
     *
     * <p>Three guards below are new, and each closes an unbounded path:
     *
     * <ul>
     *   <li><b>Already friends.</b> Nothing checked, so you could keep requesting someone
     *       who was already your friend — every call re-notified them, and accepting again
     *       just re-ran the edge writes.</li>
     *   <li><b>Duplicate request.</b> The Redis set already deduplicated the entry, but the
     *       notification fired regardless, so one attacker could send the same request in a
     *       loop and drive an unlimited stream of "X wants to be your friend" at the target.
     *       {@code add} returns 0 when the member was already present; that is the signal.
     *       Push is rate-limited, but the WebSocket notification is not, and it is the one
     *       that lands in the app.</li>
     *   <li><b>Inbox cap.</b> A pending set had no ceiling, so N attacker accounts (or one
     *       account against N targets) could grow it without limit — a 14-day Redis key per
     *       entry. Past the cap the request is refused rather than silently dropped, so the
     *       sender sees a real answer.</li>
     * </ul>
     *
     * <p>None of this is a substitute for a block list, which the product still lacks.
     */
    public void request(String from, String to) {
        if (from.equals(to)) throw new IllegalArgumentException("You cannot befriend yourself");
        // The target must actually exist. Without this the endpoint accepted any string —
        // a fuzz run got HTTP 200 for a friend request to an emoji username — writing a
        // pending-request entry (and a 14-day Redis key) for an account that will never
        // exist. That is unbounded attacker-controlled key growth, and it also let a
        // caller probe/populate arbitrary names.
        User target = userRepository.findByUsername(to)
            .orElseThrow(() -> new IllegalArgumentException("No such player: " + to));

        User sender = economyService.walletFor(from);
        if (userRepository.countFriendEdge(sender.getId(), target.getId()) > 0) {
            throw new IllegalArgumentException(to + " is already your friend");
        }

        boolean isNew;
        try {
            if (redis.opsForSet().size(PENDING_KEY + to) != null
                    && redis.opsForSet().size(PENDING_KEY + to) >= MAX_PENDING_PER_PLAYER
                    && !Boolean.TRUE.equals(redis.opsForSet().isMember(PENDING_KEY + to, from))) {
                throw new IllegalStateException(to + " has too many pending friend requests");
            }
            Long added = redis.opsForSet().add(PENDING_KEY + to, from);
            redis.expire(PENDING_KEY + to, PENDING_TTL);
            isNew = added != null && added > 0;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            degraded(e);
            Set<String> pending = localPending.computeIfAbsent(to, x -> ConcurrentHashMap.newKeySet());
            if (pending.size() >= MAX_PENDING_PER_PLAYER && !pending.contains(from)) {
                throw new IllegalStateException(to + " has too many pending friend requests");
            }
            isNew = pending.add(from);
        }

        if (!isNew) {
            // Already in their inbox. Re-requesting is not an error, but it must not
            // generate another notification.
            logger.debug("Duplicate friend request {} -> {} — not re-notifying", from, to);
            return;
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
        // One statement per edge rather than rewriting each player's whole friend
        // collection — see UserRepository.linkFriend for why the read-modify-write lost
        // edges and left one-way friendships behind.
        userRepository.linkFriend(a.getId(), b.getId());
        userRepository.linkFriend(b.getId(), a.getId());
        notify(requester, me + " accepted your friend request!");
        logger.info("Friendship formed: {} <-> {}", me, requester);
    }

    public void decline(String me, String requester) {
        removePending(me, requester);
    }

    @Transactional
    public void remove(String me, String exFriend) {
        // walletFor PROVISIONS a row for an unknown name, so calling it on an unvalidated
        // path variable turned DELETE /api/friends/{anything} into a row factory: one
        // authenticated account could mint a users row per request just by varying the
        // path. `request` was hardened against exactly this ("unbounded attacker-controlled
        // key growth"); `remove` was missed. Look the target up instead of provisioning
        // it, and treat an unknown name as a no-op — un-friending someone who does not
        // exist has already achieved what the caller wanted.
        var target = userRepository.findByUsername(exFriend);
        if (target.isEmpty()) {
            logger.debug("Ignoring un-friend of unknown player {}", exFriend);
            return;
        }
        User a = economyService.walletFor(me);
        User b = target.get();
        // Same reason as accept: deleting the one edge cannot clobber a concurrent change
        // to the rest of either player's list.
        userRepository.unlinkFriend(a.getId(), b.getId());
        userRepository.unlinkFriend(b.getId(), a.getId());
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

    /**
     * Whether {@code a} and {@code b} are friends. Reads the join table directly rather than
     * loading either user's whole collection.
     *
     * <p>Deliberately does NOT provision a wallet for an unknown name — it is called from an
     * authorization check on a caller-supplied identity, and {@code walletFor} creates rows.
     * That is the row-factory shape {@code request} and {@code remove} were both hardened
     * against; an authorization predicate is the last place that should mint anything.
     */
    public boolean areFriends(String a, String b) {
        if (a == null || b == null || a.equals(b)) return false;
        var ua = userRepository.findByUsername(a);
        var ub = userRepository.findByUsername(b);
        if (ua.isEmpty() || ub.isEmpty()) return false;
        return userRepository.countFriendEdge(ua.get().getId(), ub.get().getId()) > 0;
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
