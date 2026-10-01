package com.qyuro.joinflow.storage;

import com.qyuro.joinflow.QyuroJoinFlow;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded daemon worker for small JoinFlow-owned file work. No Minecraft world objects enter it. */
public final class JoinFlowIO implements AutoCloseable {
    private final AtomicBoolean warnedFull = new AtomicBoolean();
    private final ThreadPoolExecutor executor;

    public JoinFlowIO() {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "QyuroJoinFlow-IO");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, exception) ->
                    QyuroJoinFlow.LOGGER.error("[QyuroJoinFlow] ERROR component=io exception=uncaught worker failure", exception));
            return thread;
        };
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean submit(Runnable operation) {
        if (executor.isShutdown()) return false;
        try {
            executor.execute(operation);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            if (warnedFull.compareAndSet(false, true)) {
                QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] I/O queue is full; dropping an optional history/cache write");
            }
            return false;
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(500L, TimeUnit.MILLISECONDS)) {
                QyuroJoinFlow.LOGGER.warn("[QyuroJoinFlow] I/O worker did not finish promptly during shutdown; leaving queued metadata writes behind");
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
