package com.xai.sudokupro.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Turns {@code @Async} on and gives it an executor that cannot take the process down.
 *
 * <h2>Why this exists</h2>
 * {@link com.xai.sudokupro.service.NotificationService} carried {@code @Async} on
 * {@code sendNotification}, {@code sendTypedNotification} and {@code broadcastNotification},
 * but no {@code @EnableAsync} existed anywhere in the project. Without it Spring never
 * registers {@code AsyncAnnotationBeanPostProcessor}, so those beans are never proxied for
 * asynchrony and the annotation is decoration: every "asynchronous" notification ran inline
 * on whatever thread called it.
 *
 * <h2>What that cost</h2>
 * The callers are request-path code — {@code FriendService.acceptRequest},
 * {@code AchievementService} on game end, {@code DuelService} on challenge and result,
 * {@code DailyPuzzleService}, {@code WeeklyTournamentService}, {@code SeasonService} — plus
 * the startup broadcast in {@code SudokuProApplication}. Each notification does a WebSocket
 * write and then, via {@code NotificationService.deliverPush}, a
 * {@link com.xai.sudokupro.service.push.FcmPushSender} call: a blocking outbound HTTPS
 * request to Google with a ten-second timeout, preceded by a JWT token exchange on a cold
 * cache. A slow or unreachable FCM endpoint therefore pinned a Tomcat worker for the whole
 * duration of every notifying request, and a duel result (which notifies both players) paid
 * it twice.
 *
 * <h2>Why it is safe to actually run these off-thread</h2>
 * Each {@code @Async} method was checked against the usual disqualifiers before flipping the
 * switch:
 * <ul>
 *   <li><b>Request scope</b> — none of the three touches the request, the session, or any
 *       request-scoped bean; they take {@code playerId}/{@code type}/{@code message} as
 *       plain strings.</li>
 *   <li><b>Transactions</b> — none is {@code @Transactional}, and none depends on the
 *       visibility of a caller's transaction. Only strings cross the boundary, so there are
 *       no detached JPA entities or lazy collections to dereference on another thread.</li>
 *   <li><b>SecurityContext</b> — no method reads {@code SecurityContextHolder} or
 *       {@code AuthService}. (The default holder strategy does not propagate to pool threads,
 *       so had any of them needed the principal this would have silently become an anonymous
 *       call — the exact class of bug this codebase keeps producing.)</li>
 *   <li><b>Return values</b> — all three return {@code void}, so no caller is waiting on a
 *       result that would now be {@code null}.</li>
 *   <li><b>Exceptions</b> — argument validation throws {@code IllegalArgumentException},
 *       which can no longer reach the caller once the call is asynchronous. It is routed to
 *       {@link #getAsyncUncaughtExceptionHandler()} and logged with the offending arguments.
 *       Every existing call site ({@code FriendService.notify},
 *       {@code AchievementService.notify}, {@code DuelService}, {@code SeasonService},
 *       {@code DailyPuzzleService}, {@code WeeklyTournamentService}) already wraps the call
 *       in {@code try/catch} and only logs, so no caller loses behaviour it relied on.</li>
 * </ul>
 *
 * <h2>Why a dedicated executor</h2>
 * With {@code @EnableAsync} and no {@link AsyncConfigurer}, Spring resolves the executor by
 * type; finding several {@code TaskExecutor} beans here ({@code AppConfig.taskExecutor},
 * Boot's {@code applicationTaskExecutor}) it falls back to a by-name lookup and, failing
 * that, to {@code SimpleAsyncTaskExecutor} — which starts a <em>new thread per invocation</em>
 * with no ceiling, turning a notification storm into an OutOfMemoryError. Implementing
 * {@code AsyncConfigurer} makes the choice explicit rather than dependent on which other
 * beans happen to exist.
 *
 * <p>The pool is bounded in all three dimensions and rejects with {@code CallerRunsPolicy}:
 * when both queue and pool are full the notification degrades to the synchronous behaviour we
 * had before, rather than throwing {@code RejectedExecutionException} into call sites that
 * treat notification failure as unimportant and would drop it silently.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);

    /** Thread-name prefix; {@code AsyncWiringTest} asserts on it to prove which executor is live. */
    public static final String THREAD_PREFIX = "SudokuPro-Async-";

    @Value("${sudokupro.async.core-pool-size:4}")
    private int corePoolSize;

    @Value("${sudokupro.async.max-pool-size:16}")
    private int maxPoolSize;

    @Value("${sudokupro.async.queue-capacity:500}")
    private int queueCapacity;

    /**
     * Executor backing every {@code @Async} invocation.
     *
     * <p>Deliberately separate from {@code AppConfig.taskExecutor} (the generic pool) so a
     * notification burst cannot starve unrelated work, and so a thread dump names the
     * subsystem responsible.
     */
    /** Bean name, referenced by every {@code @Async} site so resolution cannot drift. */
    public static final String ASYNC_EXECUTOR = "applicationAsyncExecutor";

    @Bean(name = ASYNC_EXECUTOR)
    public ThreadPoolTaskExecutor applicationAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(THREAD_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let in-flight notifications finish on shutdown instead of being dropped mid-send.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calls the {@code @Bean} method rather than constructing a pool: this class is
     * CGLIB-enhanced, so the call is intercepted and returns the single container-managed
     * instance. Building one here directly would leave a second, unmanaged pool that the
     * container never shuts down.
     */
    @Override
    public Executor getAsyncExecutor() {
        return applicationAsyncExecutor();
    }

    /**
     * Async {@code void} methods have nowhere to throw. Without this handler Spring logs the
     * stack trace with no context about which call produced it, so an argument-validation
     * failure (blank playerId, blank message) would be indistinguishable from a delivery
     * failure.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) ->
            logger.error("Async {}.{} failed with args {}: {}",
                method.getDeclaringClass().getSimpleName(), method.getName(),
                Arrays.toString(params), ex.toString(), ex);
    }
}
