package com.xai.sudokupro.ui;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts the JavaFX toolkit once per JVM and runs work on the FX Application Thread.
 *
 * <p>The desktop client's whole UI package was untestable without this, which is why it
 * had one test class (theme resolution, pure logic) and no coverage of {@code BoardView}
 * at all — the class where a P1 lived for several passes.
 *
 * <p><b>A display is required, not optional.</b> JavaFX 21's public artifacts do not ship
 * Monocle, so the usual {@code -Dglass.platform=Monocle} headless trick fails with a null
 * {@code PlatformFactory}. Surefire therefore runs this module under {@code xvfb-run}
 * (see the client POM and the CI workflow). If the toolkit cannot start, these tests
 * FAIL with that explanation rather than skipping: a UI suite that silently disables
 * itself is the same defect this project has now fixed twice elsewhere.
 */
final class FxToolkit {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private FxToolkit() {
    }

    static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            // Another test class in the same JVM booted it first.
            ready.countDown();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "The JavaFX toolkit could not start, so the desktop UI is going untested. "
                + "This module needs a display: run it under xvfb-run (the client POM does "
                + "this via surefire). Cause: " + e, e);
        }
        try {
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The JavaFX toolkit did not start within 30s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the JavaFX toolkit", e);
        }
    }

    /** Runs {@code work} on the FX thread and returns its value, rethrowing any failure. */
    static <T> T onFxThread(FxSupplier<T> work) {
        start();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.get());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("FX work did not complete within 30s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for FX work", e);
        }
        Throwable t = error.get();
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error err) throw err;
        if (t != null) throw new IllegalStateException(t);
        return result.get();
    }

    /** Runs {@code work} on the FX thread, waiting for it to finish. */
    static void onFxThread(Runnable work) {
        onFxThread(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Drains the FX event queue, so work posted with {@code Platform.runLater} by the code
     * under test has run before assertions look at the scene graph.
     */
    static void settle() {
        onFxThread(() -> null);
    }

    @FunctionalInterface
    interface FxSupplier<T> {
        T get() throws Exception;
    }
}
