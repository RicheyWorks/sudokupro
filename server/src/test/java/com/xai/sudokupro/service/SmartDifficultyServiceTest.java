package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.util.SecureRandomGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Adaptive difficulty: three fast clean solves promote, three slow signals demote. */
class SmartDifficultyServiceTest {

    private SmartDifficultyService service;

    @BeforeEach
    void setUp() {
        StringRedisTemplate downRedis = mock(StringRedisTemplate.class,
            inv -> { throw new RedisConnectionFailureException("down (test)"); });
        service = new SmartDifficultyService(downRedis);
    }

    @Test
    void newPlayersStartAtTwo() {
        assertEquals(2, service.recommendedDifficulty("richmond"));
    }

    @Test
    void threeFastCleanSolvesPromote() {
        for (int i = 0; i < 3; i++) service.onGameEnded(fastClean(2), "richmond");
        assertEquals(3, service.recommendedDifficulty("richmond"));

        // Cap at 4
        for (int i = 0; i < 6; i++) service.onGameEnded(fastClean(4), "richmond");
        assertEquals(4, service.recommendedDifficulty("richmond"));
    }

    @Test
    void threeSlowSignalsDemoteToTheFloor() {
        for (int i = 0; i < 3; i++) service.onGameEnded(abandoned(), "richmond");
        assertEquals(1, service.recommendedDifficulty("richmond"));
        for (int i = 0; i < 3; i++) service.onGameEnded(abandoned(), "richmond");
        assertEquals(1, service.recommendedDifficulty("richmond"), "floor is 1");
    }

    @Test
    void hintedOrBelowLevelSolvesDoNotPromoteAndInterruptTheStreak() {
        service.onGameEnded(fastClean(2), "richmond");
        service.onGameEnded(fastClean(2), "richmond");

        SudokuBoard hinted = fastClean(2);
        hinted.incrementHintCount();
        service.onGameEnded(hinted, "richmond"); // neutral: solved, not fast-clean — no reset either way

        SudokuBoard easy = fastClean(1); // below current level: doesn't count as fast
        service.onGameEnded(easy, "richmond");

        assertEquals(2, service.recommendedDifficulty("richmond"),
            "only fast clean solves at (or above) the recommended level promote");
    }

    @Test
    void slowStreakIsResetByAFastSolve() {
        service.onGameEnded(abandoned(), "richmond");
        service.onGameEnded(abandoned(), "richmond");
        service.onGameEnded(fastClean(2), "richmond"); // resets the slow streak
        service.onGameEnded(abandoned(), "richmond");
        service.onGameEnded(abandoned(), "richmond");

        assertEquals(2, service.recommendedDifficulty("richmond"));
    }

    private SudokuBoard fastClean(int difficulty) {
        SudokuBoard real = new SudokuBoard(1, false, false, 0, "g-" + System.nanoTime());
        new AISolverService(new SecureRandomGenerator(new SimpleMeterRegistry())).solveSudoku(real);
        real.setDifficulty(difficulty);
        SudokuBoard board = spy(real);
        doReturn(Duration.ofSeconds(120)).when(board).getSolveTime();
        return board;
    }

    private SudokuBoard abandoned() {
        return new SudokuBoard(1, false, false, 0, "g-quit-" + System.nanoTime());
    }
}
