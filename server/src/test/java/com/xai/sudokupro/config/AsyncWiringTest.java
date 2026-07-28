package com.xai.sudokupro.config;

import com.xai.sudokupro.service.NotificationService;
import com.xai.sudokupro.websocket.MultiplayerBroadcaster;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defect class: <b>an annotation that is present but never enabled</b> — the same family as
 * the security-guard bean Spring never instantiated and the WebSocket heartbeat with no caller.
 *
 * <p>{@link NotificationService} carries {@code @Async} on three methods. Without
 * {@code @EnableAsync} anywhere in the application, the annotation is inert: no
 * {@code AsyncAnnotationBeanPostProcessor} is registered, no proxy is created, and every
 * "asynchronous" notification runs inline on the caller's thread. The caller here is an HTTP
 * request thread (friend accept, achievement unlock, duel result) or the scheduler, and the
 * body reaches {@code FcmPushSender}, which performs a blocking outbound HTTPS call to Google
 * with a 10-second timeout — so a slow FCM response held a Tomcat worker for the whole time.
 *
 * <p>These tests assert the <em>wiring</em>, not the notification logic: they fail if
 * {@code @EnableAsync} is removed, and they fail if {@code @Async} silently falls back to
 * Spring's default {@code SimpleAsyncTaskExecutor}, which spawns an unbounded number of
 * threads and is an outage of its own.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(AsyncWiringTest.Doubles.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:asyncwiring;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "sudokupro.ui.enabled=false"
})
class AsyncWiringTest {

    /**
     * A real recording double rather than a Mockito {@code @MockBean}.
     *
     * <p>Mockito's stubbing is a two-step dance — {@code doAnswer(...)} then
     * {@code .when(mock).method(...)} — and it is not thread-safe. EventEngine's
     * {@code @Scheduled} jobs call {@code broadcastEvent} on this very bean from the
     * scheduler's thread, so a job firing in the window between those two steps makes
     * Mockito throw {@code UnfinishedStubbing} and the test errors out for a reason that
     * has nothing to do with what it tests. Observed exactly once in a full-suite run,
     * which is how these present.
     *
     * <p>A plain object with a concurrent queue has no stubbing phase at all, so there is
     * no window to race. This is the second flake in this class from the same root cause
     * (the first recorded every invocation and read back the scheduler's, not its own) —
     * the shared-mock pattern was the defect both times, so it is gone rather than
     * patched again.
     */
    static class RecordingBroadcaster extends MultiplayerBroadcaster {
        final java.util.concurrent.ConcurrentLinkedQueue<String[]> calls =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

        RecordingBroadcaster() {
            super(null, null);
        }

        @Override
        public void broadcastEvent(String type, String message, String gameId) {
            // Deliberately does NOT call super: the real body reaches a null session
            // registry here, and the only thing under test is which thread arrives.
            calls.add(new String[]{message, Thread.currentThread().getName()});
        }

        /** The thread that handled {@code message}, or null within the timeout. */
        String threadFor(String message, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                for (String[] c : calls) {
                    if (message.equals(c[0])) return c[1];
                }
                Thread.sleep(25);
            }
            return null;
        }
    }

    @TestConfiguration
    static class Doubles {
        @Bean
        @Primary
        RecordingBroadcaster recordingBroadcaster() {
            return new RecordingBroadcaster();
        }
    }

    @Autowired
    private RecordingBroadcaster broadcaster;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    /**
     * The proof that {@code @Async} is live: the annotated method body must execute on a
     * thread that is neither the caller's nor a JDK common-pool thread, but one owned by the
     * application's own async executor.
     */
    @Test
    void asyncNotificationLeavesTheCallerThread() throws Exception {
        // The probe must be able to recognise ITS OWN invocation. EventEngine's scheduled
        // jobs (cosmic duel, drip showdown, daily challenge) call broadcastEvent on this
        // same bean from the scheduler's thread, so recording every invocation meant the
        // queue could hand back "scheduling-1" — a job this test never triggered — and the
        // assertion then reported a wiring failure that did not exist. It passed alone and
        // failed in a full-suite run purely on timing, which is the signature of a test
        // racing the application rather than testing it.
        String probe = "async wiring probe " + java.util.UUID.randomUUID();

        String callerThread = Thread.currentThread().getName();
        notificationService.broadcastNotification("system", probe);

        // Matched by the probe's own unique message, so a scheduled job's broadcast
        // arriving first cannot be mistaken for this one.
        String workerThread = broadcaster.threadFor(probe, 10_000);
        assertThat(workerThread)
            .as("@Async body never ran — either it ran inline (no @EnableAsync) or not at all")
            .isNotNull();
        assertThat(workerThread)
            .as("@Async body ran on the caller's thread: @EnableAsync is missing, so the "
                + "annotation is inert and the caller is blocked for the whole notification")
            .isNotEqualTo(callerThread);
        assertThat(workerThread)
            .as("@Async ran off-thread but not on the configured application executor — a "
                + "fallback executor (SimpleAsyncTaskExecutor spawns unbounded threads) is in use")
            .startsWith("SudokuPro-Async-");
    }

    /**
     * Enabling {@code @Async} without supplying an executor hands the work to
     * {@code SimpleAsyncTaskExecutor}, which starts a brand-new thread per invocation with no
     * ceiling. Assert the executor that actually backs {@code @Async} is bounded in every
     * dimension and degrades by running on the caller rather than throwing
     * {@code RejectedExecutionException} at a notification site that ignores failures.
     */
    @Test
    void asyncExecutorIsBoundedAndDegradesToCallerRuns() {
        ThreadPoolTaskExecutor executor =
            context.getBean("applicationAsyncExecutor", ThreadPoolTaskExecutor.class);

        assertThat(executor.getCorePoolSize()).isPositive();
        assertThat(executor.getMaxPoolSize())
            .as("unbounded max pool size is an unbounded thread count")
            .isLessThan(Integer.MAX_VALUE);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("SudokuPro-Async-");
        assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity())
            .as("an unbounded queue converts thread exhaustion into heap exhaustion")
            .isLessThan(Integer.MAX_VALUE);
        assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
            .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }

    @Autowired(required = false)
    private org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    private org.springframework.context.ApplicationContext ctx;

    /**
     * Scheduled work must have a pool, not a single thread.
     *
     * <p>No {@code TaskScheduler} bean existed and no {@code spring.task.scheduling.*}
     * property was set, so Spring Boot's default pool size of ONE applied and all nine
     * {@code @Scheduled} jobs ran strictly serially on it. One of them is not like the
     * others: {@code AntiCheatScheduler.scanForCheaters} pages 500 boards a minute with a
     * {@code findById} per board, and carries
     * {@code @Retryable(maxAttempts = 3, backoff = 5000ms)} while rethrowing — so a failing
     * scan sleeps ten seconds on the scheduling thread and repeats the whole pass three
     * times.
     *
     * <p>Sharing that thread is {@code MultiplayerBroadcaster.broadcastHealthPing}, the
     * 20-second keep-alive that exists to stop proxies reaping idle gameplay sockets. While
     * the scan holds the only thread the heartbeat does not fire, load balancers reap the
     * sockets, and players are disconnected mid-game by a background job that has nothing to
     * do with them.
     */
    @Test
    void scheduledJobsGetARealPoolRatherThanASingleSharedThread() {
        assertThat(taskScheduler)
            .as("a dedicated TaskScheduler bean must exist, or Boot defaults to one thread")
            .isNotNull();
        assertThat(taskScheduler.getPoolSize())
            .as("the anti-cheat scan must not be able to monopolise scheduled execution")
            .isGreaterThan(1);
    }

    /**
     * The leaderboard refresh must actually be scheduled.
     *
     * <p>It was a bare {@code Runnable} bean that nothing consumed — no {@code @Scheduled},
     * no {@code TaskScheduler.schedule}, no {@code ApplicationRunner}. It read as a working
     * scheduler in review and had never run. {@code refreshLeaderboard} is the only
     * {@code @CacheEvict} for the leaderboard caches and the only reset of the recent-score
     * window, so in production neither ever happened: {@code pointsDelta}, documented as a
     * "recent" figure, silently reported the lifetime total instead.
     */
    @Test
    void theLeaderboardRefreshIsAScheduledJobAndNotAnOrphanedRunnable() {
        AppConfig.LeaderboardRefreshJob job = ctx.getBean(AppConfig.LeaderboardRefreshJob.class);
        assertThat(job).isNotNull();

        java.lang.reflect.Method refresh;
        try {
            refresh = AppConfig.LeaderboardRefreshJob.class.getMethod("refresh");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("refresh() must exist and be public for Spring to schedule it", e);
        }
        assertThat(refresh.isAnnotationPresent(
                org.springframework.scheduling.annotation.Scheduled.class))
            .as("an unscheduled refresh is the bug: the leaderboard caches would never be evicted")
            .isTrue();
    }
}
