package com.xai.sudokupro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.sudokupro.model.EnhancedMove;
import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.util.SecureRandomGenerator;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Two GameService instances ("pods") over ONE simulated Redis and ONE database —
 * the multi-replica shape the known-open "cross-replica activeGames staleness"
 * defect lived in.
 *
 * <p>The defect: activeGames is a per-pod read-through cache and getGame trusted a
 * cache hit forever, so after pod A wrote a game, pod B kept serving — and worse,
 * mutating — its old copy. GameLockManager serialises the writes but says nothing
 * about the cached reads, so the lost update happened <em>under the lock</em>.
 *
 * <p>The fix under test: every write bumps a per-game version counter in Redis
 * (after the board write, inside the game lock) and every cache hit validates
 * against it before being trusted. Boards cross the simulated Redis through a real
 * Jackson round-trip (the same JavaTimeModule mapper shape RedisConfig builds), so
 * each pod holds a genuinely distinct object graph, exactly as in production.
 */
class GameServiceCacheCoherenceTest {

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Simulated shared Redis: board keyspace (JSON, forcing serialization) + strings. */
    private final Map<String, String> redisBoards  = new ConcurrentHashMap<>();
    private final Map<String, String> redisStrings = new ConcurrentHashMap<>();
    /** Simulated shared database. */
    private final Map<String, String> dbRows = new ConcurrentHashMap<>();

    @BeforeEach
    void reset() {
        redisBoards.clear();
        redisStrings.clear();
        dbRows.clear();
    }

    private SudokuBoard fromJson(String json) {
        try {
            return json == null ? null : mapper.readValue(json, SudokuBoard.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(SudokuBoard board) {
        try {
            return mapper.writeValueAsString(board);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A pod: its own GameService, caches and locks; Redis and DB shared with siblings. */
    @SuppressWarnings("unchecked")
    private GameService pod(boolean versionRedisUp) {
        RedisTemplate<String, SudokuBoard> boardRedis = mock(RedisTemplate.class);
        ValueOperations<String, SudokuBoard> boardOps = mock(ValueOperations.class);
        lenient().when(boardRedis.opsForValue()).thenReturn(boardOps);
        lenient().doAnswer(inv -> {
            redisBoards.put(inv.getArgument(0), toJson(inv.getArgument(1)));
            return null;
        }).when(boardOps).set(anyString(), any(SudokuBoard.class), anyLong(), any());
        lenient().when(boardOps.get(anyString()))
            .thenAnswer(inv -> fromJson(redisBoards.get(inv.<String>getArgument(0))));
        lenient().when(boardRedis.delete(anyString()))
            .thenAnswer(inv -> redisBoards.remove(inv.<String>getArgument(0)) != null);

        StringRedisTemplate versionRedis;
        if (versionRedisUp) {
            versionRedis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> verOps = mock(ValueOperations.class);
            lenient().when(versionRedis.opsForValue()).thenReturn(verOps);
            lenient().when(verOps.get(anyString()))
                .thenAnswer(inv -> redisStrings.get(inv.<String>getArgument(0)));
            lenient().when(verOps.increment(anyString())).thenAnswer(inv -> {
                String bumped = redisStrings.merge(inv.getArgument(0), "1",
                    (old, one) -> String.valueOf(Long.parseLong(old) + 1));
                return Long.parseLong(bumped);
            });
            lenient().when(versionRedis.expire(anyString(), anyLong(), any())).thenReturn(true);
        } else {
            versionRedis = mock(StringRedisTemplate.class,
                inv -> { throw new RedisConnectionFailureException("down (test)"); });
        }

        GameRepository repo = mock(GameRepository.class);
        lenient().when(repo.save(any(SudokuBoard.class))).thenAnswer(inv -> {
            SudokuBoard b = inv.getArgument(0);
            if (b.getGameId() != null) dbRows.put(b.getGameId(), toJson(b));
            return b;
        });
        lenient().when(repo.findByGameId(anyString()))
            .thenAnswer(inv -> fromJson(dbRows.get(inv.<String>getArgument(0))));

        // Per-pod local-only locks (the sequential test never contends anyway).
        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });

        SecureRandomGenerator rng = new SecureRandomGenerator(new SimpleMeterRegistry());
        GameService service = new GameService(
            new AISolverService(rng), repo, mock(MultiplayerBroadcaster.class),
            boardRedis, rng, new PlayerStateStore(downRedis), new GameLockManager(downRedis),
            mock(AnalyticsService.class), mock(AntiCheatEngine.class));
        service.setVersionRedis(versionRedis);
        return service;
    }

    /** Finds an empty cell and a legal value for it. Returns {row, col, value}. */
    private static int[] findLegalMove(SudokuBoard board) {
        SudokuCell[][] cells = board.getBoard();
        for (int i = 0; i < 9; i++)
            for (int j = 0; j < 9; j++)
                if (cells[i][j].getValue() == 0)
                    for (int v = 1; v <= 9; v++)
                        if (board.isValidMove(i, j, v)) return new int[]{i, j, v};
        throw new IllegalStateException("Generated board has no legal move");
    }

    /**
     * The defect itself: pod B caches a board, pod A applies a move, pod B must NOT
     * keep serving its pre-move copy.
     */
    @Test
    void aReplicaDropsItsCachedCopyWhenAnotherReplicaWritesTheGame() {
        GameService podA = pod(true);
        GameService podB = pod(true);

        SudokuBoard boardA = podA.createNewGame(1, "p1", false, false);
        String gameId = boardA.getGameId();

        SudokuBoard staleCandidate = podB.getGame(gameId); // hydrates pod B's cache
        int[] m = findLegalMove(staleCandidate);

        assertTrue(podA.applyMove(gameId,
            new EnhancedMove(m[0], m[1], m[2], SudokuCell.MoveSource.PLAYER), "p1"),
            "test setup: the move must apply on pod A");

        SudokuBoard reloaded = podB.getGame(gameId);
        assertEquals(m[2], reloaded.getBoard()[m[0]][m[1]].getValue(),
            "pod B must serve the move pod A applied, not its cached pre-move copy");
        assertNotSame(staleCandidate, reloaded,
            "the stale cached instance must have been dropped, not patched in place");
        assertEquals(0, staleCandidate.getBoard()[m[0]][m[1]].getValue(),
            "sanity: the old copy really was stale — without invalidation it is what pod B would have served");
    }

    /** A pod's own writes must not invalidate its own cache (no reload churn). */
    @Test
    void aPodKeepsItsOwnCacheHotAcrossItsOwnWrites() {
        GameService podA = pod(true);

        SudokuBoard board = podA.createNewGame(1, "p1", false, false);
        String gameId = board.getGameId();
        int[] m = findLegalMove(board);
        podA.applyMove(gameId, new EnhancedMove(m[0], m[1], m[2], SudokuCell.MoveSource.PLAYER), "p1");

        assertSame(board, podA.getGame(gameId),
            "the writing pod's copy is fresh by construction and must survive validation");
    }

    /**
     * Redis down → validation is impossible → the cache is trusted, matching
     * GameLockManager's documented single-replica degradation in the same state.
     */
    @Test
    void validationDegradesToTrustingTheCacheWhenRedisIsDown() {
        GameService pod = pod(false);

        SudokuBoard board = pod.createNewGame(1, "p1", false, false);
        assertSame(board, pod.getGame(board.getGameId()),
            "with Redis unreachable the cached copy must be served, not an exception");
    }
}
