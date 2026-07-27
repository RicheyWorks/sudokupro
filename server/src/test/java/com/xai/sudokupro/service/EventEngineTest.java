package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuGenerator;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Event scheduling lifecycle.
 *
 * <p>Regression: {@code triggerDailyChallenge}/{@code triggerCosmicDuel}/
 * {@code triggerDripShowdown} called the private {@code schedule*} methods, which do NOT
 * run one cycle — each installs ANOTHER permanent {@code scheduleAtFixedRate} task.
 * {@code EventScheduler} calls the triggers on its own fixed rate, so every tick stacked a
 * new repeating schedule on top of the ones already running: after a day roughly 97
 * concurrent drip-showdown schedules, each generating a board, persisting it and
 * broadcasting to every connected client every 15 minutes, growing linearly forever
 * against a 3-thread pool.
 */
@ExtendWith(MockitoExtension.class)
class EventEngineTest {

    @Mock private UserRepository userRepository;
    @Mock private AntiCheatEngine antiCheatEngine;
    @Mock private AnalyticsService analyticsService;
    @Mock private SudokuGenerator sudokuGenerator;
    @Mock private GameRepository gameRepository;
    @Mock private MultiplayerBroadcaster broadcaster;

    private EventEngine engine;

    @BeforeEach
    void setUp() {
        lenient().when(sudokuGenerator.generate(any(), anyBoolean(), anyBoolean(), anyLong(),
                anyBoolean(), anyBoolean(), anyInt()))
            .thenAnswer(inv -> new SudokuBoard(1, false, false, 0, "evt-" + System.nanoTime()));
        lenient().when(gameRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepository.findActiveStreakersInPeriod(any(), any(), anyInt()))
            .thenReturn(List.of());
        engine = new EventEngine(userRepository, antiCheatEngine, analyticsService,
            sudokuGenerator, gameRepository, broadcaster);
        // The constructor no longer self-starts anything — see the note below. Settling
        // here anyway keeps the timing shape of these tests honest.
        settle();
    }

    private static void settle() {
        try { Thread.sleep(900); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test
    void triggeringAnEventRunsItsCycleInlineRatherThanSchedulingIt() {
        clearInvocations(broadcaster);

        engine.triggerDripShowdown();

        // Inline: the broadcast has already happened by the time the call returns. Under
        // the old code the trigger merely INSTALLED a repeating schedule, so nothing was
        // guaranteed to have run yet — and that schedule then fired forever.
        verify(broadcaster).broadcastEvent(eq("drip_showdown"), anyString(), anyString());
    }

    @Test
    void repeatedTriggersDoNotStackAdditionalRecurringSchedules() {
        int before = engine.getActiveEvents().size();

        for (int i = 0; i < 5; i++) engine.triggerDripShowdown();

        int afterTriggers = engine.getActiveEvents().size();
        assertEquals(before + 5, afterTriggers, "five triggers must produce five events — one each");

        // The decisive check: if each trigger had installed its own fixed-rate schedule,
        // those schedules would keep firing in the background after the calls returned.
        settle();
        assertEquals(afterTriggers, engine.getActiveEvents().size(),
            "no further events may appear once the triggers have returned — "
            + "extra firings mean extra recurring schedules were installed");
    }

    /**
     * A body that throws must not retire the recurring schedule.
     * {@code scheduleAtFixedRate} suppresses every subsequent execution if the task throws,
     * and swallows the exception into the ScheduledFuture — so one transient generator or
     * DB failure used to silently kill the event stream for the life of the JVM.
     */
    @Test
    void aFailingCycleIsContainedAndDoesNotPropagate() {
        lenient().when(sudokuGenerator.generate(any(), anyBoolean(), anyBoolean(), anyLong(),
                anyBoolean(), anyBoolean(), anyInt()))
            .thenThrow(new IllegalStateException("generator exhausted"));

        assertDoesNotThrow(engine::triggerDripShowdown,
            "a failed cycle must be logged and contained, not thrown at the scheduler");
    }

    /**
     * The constructor must NOT install recurring schedules.
     *
     * <p>It used to, and {@link com.xai.sudokupro.service.EventScheduler} independently
     * drives the very same cycles on the very same periods — so every cosmic event fired
     * TWICE per period per pod. {@code EventScheduler}'s gap guard is its own in-memory map
     * and could not see this engine's executor, so it never suppressed the duplicates.
     *
     * <p>The players felt it: {@code runDripShowdown} broadcasts to every connected client,
     * so a 15-minute showdown announced itself twice per period — 192 toasts a day instead
     * of 96, tripled across three replicas — and {@code runCosmicDuel} sent duplicate duel
     * invitations naming different game ids to each eligible streaker.
     */
    @Test
    void constructingTheEngineDoesNotStartRecurringEventCycles() {
        clearInvocations(broadcaster);

        settle();

        verifyNoInteractions(broadcaster);
        assertTrue(engine.getActiveEvents().isEmpty(),
            "a freshly constructed engine must run no cycles of its own — EventScheduler "
                + "is the single driver, and two drivers means every event fires twice");
    }
}
