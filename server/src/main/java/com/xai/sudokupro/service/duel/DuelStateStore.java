package com.xai.sudokupro.service.duel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Duel records shared across replicas (Redis JSON values with a 24h TTL —
 * duels are ephemeral), with the established degrade-to-local-map fallback.
 * Win claims use SETNX so two replicas can't crown two winners.
 */
@Component
public class DuelStateStore {

    private static final Logger logger = LoggerFactory.getLogger(DuelStateStore.class);
    private static final String DUEL_KEY = "sudokupro:duel:";           // + duelId → JSON record
    private static final String PLAYER_KEY = "sudokupro:duel:player:";  // + playerId → set of duelIds
    private static final String WINNER_KEY = "sudokupro:duel:winner:";  // + duelId → playerId (SETNX)
    private static final Duration DUEL_TTL = Duration.ofHours(24);

    /** Mutable-by-copy duel record; status transitions PENDING → ACTIVE → FINISHED (or DECLINED). */
    public record DuelRecord(String duelId, String challenger, String opponent,
                             String status, String winner, int difficulty) {
        public DuelRecord withStatus(String newStatus) {
            return new DuelRecord(duelId, challenger, opponent, newStatus, winner, difficulty);
        }
        public DuelRecord withWinner(String newWinner) {
            return new DuelRecord(duelId, challenger, opponent, "FINISHED", newWinner, difficulty);
        }
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean degradedLogged = new AtomicBoolean(false);

    private final Map<String, String> localDuels = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> localByPlayer = new ConcurrentHashMap<>();
    private final Map<String, String> localWinners = new ConcurrentHashMap<>();

    public DuelStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(DuelRecord duel) {
        String json = toJson(duel);
        try {
            redis.opsForValue().set(DUEL_KEY + duel.duelId(), json, DUEL_TTL);
            for (String p : new String[]{duel.challenger(), duel.opponent()}) {
                redis.opsForSet().add(PLAYER_KEY + p, duel.duelId());
                redis.expire(PLAYER_KEY + p, DUEL_TTL);
            }
        } catch (Exception e) {
            degraded(e);
            localDuels.put(duel.duelId(), json);
            for (String p : new String[]{duel.challenger(), duel.opponent()}) {
                localByPlayer.computeIfAbsent(p, x -> ConcurrentHashMap.newKeySet()).add(duel.duelId());
            }
        }
    }

    public DuelRecord find(String duelId) {
        String json;
        try {
            json = redis.opsForValue().get(DUEL_KEY + duelId);
        } catch (Exception e) {
            degraded(e);
            json = localDuels.get(duelId);
        }
        return json == null ? null : fromJson(json);
    }

    public List<DuelRecord> findForPlayer(String playerId) {
        Set<String> ids;
        try {
            ids = redis.opsForSet().members(PLAYER_KEY + playerId);
        } catch (Exception e) {
            degraded(e);
            ids = localByPlayer.getOrDefault(playerId, Set.of());
        }
        List<DuelRecord> out = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                DuelRecord d = find(id);
                if (d != null) out.add(d);
            }
        }
        return out;
    }

    /**
     * Atomically claims the win for {@code playerId}. Returns true only for the
     * FIRST claimant — SETNX in Redis, putIfAbsent locally — so simultaneous
     * solves on different replicas can't both win.
     */
    public boolean claimWin(String duelId, String playerId) {
        try {
            Boolean first = redis.opsForValue().setIfAbsent(WINNER_KEY + duelId, playerId, DUEL_TTL);
            return Boolean.TRUE.equals(first);
        } catch (Exception e) {
            degraded(e);
            return localWinners.putIfAbsent(duelId, playerId) == null;
        }
    }

    // ---- plumbing ----

    private String toJson(DuelRecord duel) {
        try {
            return mapper.writeValueAsString(duel);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize duel " + duel.duelId(), e);
        }
    }

    private DuelRecord fromJson(String json) {
        try {
            return mapper.readValue(json, DuelRecord.class);
        } catch (Exception e) {
            logger.warn("Corrupt duel record dropped: {}", e.getMessage());
            return null;
        }
    }

    private void degraded(Exception e) {
        if (degradedLogged.compareAndSet(false, true)) {
            logger.warn("DuelStateStore: Redis unavailable — duel state held in-memory only. "
                + "Fine for a single replica; NOT shared across replicas. Cause: {}", e.getMessage());
        }
    }
}
