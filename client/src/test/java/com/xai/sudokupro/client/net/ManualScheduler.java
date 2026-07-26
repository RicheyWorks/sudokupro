package com.xai.sudokupro.client.net;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A {@link GameChannel.Scheduler} the test drives by hand.
 *
 * <p>Reconnect backoff is measured in tens of seconds. Waiting for it would make
 * these tests slow and flaky and would prove nothing extra: what matters is
 * <em>whether</em> a retry was scheduled, <em>how long</em> it was scheduled for,
 * and what happens when it runs.
 */
final class ManualScheduler implements GameChannel.Scheduler {

    private final Deque<Runnable> pending = new ArrayDeque<>();
    final List<Duration> delays = new ArrayList<>();
    boolean shutdownCalled = false;

    @Override
    public void schedule(Runnable task, Duration delay) {
        delays.add(delay);
        pending.add(task);
    }

    @Override
    public void shutdown() {
        shutdownCalled = true;
        pending.clear();
    }

    int pendingCount() {
        return pending.size();
    }

    /** Runs the oldest pending task. */
    void runNext() {
        Runnable task = pending.poll();
        if (task == null) throw new IllegalStateException("No task was scheduled");
        task.run();
    }

    /** Runs pending tasks until none are left (or the guard trips). */
    void drain() {
        int guard = 0;
        while (!pending.isEmpty()) {
            if (++guard > 100) throw new IllegalStateException("Reconnect loop did not terminate");
            pending.poll().run();
        }
    }
}
