package com.xai.sudokupro.service;

import com.xai.sudokupro.util.DifficultyTuner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TelemetryService}.
 *
 * <p><b>Defect class this protects against: a telemetry hook throwing into its caller.</b>
 * {@code TelemetryService} implements {@link DifficultyTuner}, which shared model code
 * ({@code Constants}) calls while generating puzzles. Its collaborator is injected
 * {@code @Lazy}, so the very first call can trigger bean creation, hit Redis, or resolve a
 * proxy — any of which can fail. A telemetry reading is never worth failing a puzzle
 * generation over, so the contract is: always return a number, never propagate. These tests
 * pin that contract for every failure shape the lazy proxy can produce (checked collaborator
 * exception, Spring bean-creation failure, Redis-down, and a null collaborator), and pin that
 * the neutral answer is the documented 0 rather than an arbitrary constant.
 */
@ExtendWith(MockitoExtension.class)
class TelemetryServiceTest {

    @Mock private SmartDifficultyService smartDifficulty;

    // ------------------------------------------------------------------
    // Pass-through
    // ------------------------------------------------------------------

    @Test
    void reportsTheLiveAdjustmentFactorUnchanged() {
        TelemetryService telemetry = new TelemetryService(smartDifficulty);

        when(smartDifficulty.globalAdjustmentFactor()).thenReturn(2);
        assertEquals(2, telemetry.getDifficultyAdjustmentFactor());

        when(smartDifficulty.globalAdjustmentFactor()).thenReturn(-2);
        assertEquals(-2, telemetry.getDifficultyAdjustmentFactor());

        when(smartDifficulty.globalAdjustmentFactor()).thenReturn(0);
        assertEquals(0, telemetry.getDifficultyAdjustmentFactor());
    }

    @Test
    void isUsableThroughTheDifficultyTunerHook() {
        when(smartDifficulty.globalAdjustmentFactor()).thenReturn(1);
        DifficultyTuner tuner = new TelemetryService(smartDifficulty);
        assertEquals(1, tuner.getDifficultyAdjustmentFactor());
    }

    // ------------------------------------------------------------------
    // Never throws into the caller
    // ------------------------------------------------------------------

    @Test
    void aCollaboratorFailureReportsTheNeutralFactorInsteadOfThrowing() {
        when(smartDifficulty.globalAdjustmentFactor()).thenThrow(new IllegalStateException("skill store unavailable"));
        TelemetryService telemetry = new TelemetryService(smartDifficulty);

        assertEquals(0, assertDoesNotThrow(telemetry::getDifficultyAdjustmentFactor));
    }

    /** The logger formats {@code e.getMessage()}; a message-less exception must not NPE. */
    @Test
    void anExceptionWithNoMessageIsStillHandled() {
        when(smartDifficulty.globalAdjustmentFactor()).thenThrow(new RuntimeException());
        TelemetryService telemetry = new TelemetryService(smartDifficulty);

        assertEquals(0, assertDoesNotThrow(telemetry::getDifficultyAdjustmentFactor));
    }

    /** The @Lazy proxy resolves on first use; a failed bean creation surfaces right here. */
    @Test
    void aLazyProxyThatCannotBeResolvedReportsTheNeutralFactor() {
        when(smartDifficulty.globalAdjustmentFactor())
                .thenThrow(new BeanCreationException("smartDifficultyService", "no Redis"));
        TelemetryService telemetry = new TelemetryService(smartDifficulty);

        assertEquals(0, assertDoesNotThrow(telemetry::getDifficultyAdjustmentFactor));
    }

    /** Redis down surfaces immediately (fail-fast); the tuner still answers. */
    @Test
    void redisDownReportsTheNeutralFactor() {
        when(smartDifficulty.globalAdjustmentFactor())
                .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        TelemetryService telemetry = new TelemetryService(smartDifficulty);

        assertEquals(0, assertDoesNotThrow(telemetry::getDifficultyAdjustmentFactor));
    }

    /** Defensive: the constructor does not null-check, so a null collaborator must still answer. */
    @Test
    void aNullCollaboratorReportsTheNeutralFactor() {
        TelemetryService telemetry = new TelemetryService(null);

        assertEquals(0, assertDoesNotThrow(telemetry::getDifficultyAdjustmentFactor));
    }

    @Test
    void repeatedFailuresKeepReturningTheNeutralFactor() {
        when(smartDifficulty.globalAdjustmentFactor()).thenThrow(new IllegalStateException("still down"));
        TelemetryService telemetry = new TelemetryService(smartDifficulty);

        for (int i = 0; i < 5; i++) {
            assertEquals(0, telemetry.getDifficultyAdjustmentFactor());
        }
        verify(smartDifficulty, times(5)).globalAdjustmentFactor();
    }

    // ------------------------------------------------------------------
    // Against the real collaborator
    // ------------------------------------------------------------------

    /**
     * With a real {@link SmartDifficultyService} and no recorded player history, the hook
     * reports exactly 0 — the documented "no telemetry yet" answer that {@code Constants}
     * relies on at startup.
     */
    @Test
    void aRealCollaboratorWithNoHistoryReportsExactlyZero() {
        SmartDifficultyService real = new SmartDifficultyService(mock(StringRedisTemplate.class));
        TelemetryService telemetry = new TelemetryService(real);

        assertEquals(0, telemetry.getDifficultyAdjustmentFactor());
    }

    /**
     * Same, with a Redis template that fails fast on every call — the behaviour the
     * fail-fast Lettuce configuration now guarantees. The skill read is exercised first so
     * the Redis-down path is genuinely taken, then the tuner is asked for its factor.
     */
    @Test
    void aRealCollaboratorWithRedisDownReportsZeroWithoutThrowing() {
        StringRedisTemplate down = mock(StringRedisTemplate.class);
        when(down.opsForHash()).thenThrow(new RedisConnectionFailureException("connection refused"));
        SmartDifficultyService real = new SmartDifficultyService(down);
        TelemetryService telemetry = new TelemetryService(real);

        assertEquals(2, real.recommendedDifficulty("p1"), "Redis-down degrades to the default level");
        assertEquals(0, assertDoesNotThrow(telemetry::getDifficultyAdjustmentFactor));
    }
}
