package dev.toka.economy.storage;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/** One background thread runs the writes in order, so the main thread never touches the disk. A failure goes to onError. */
public final class Writer {
    @FunctionalInterface
    public interface Task {
        void run() throws IOException;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tokaEconomy-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final BiConsumer<String, Throwable> onError;

    /** @param onError gets the language key of the failure and the exception */
    public Writer(BiConsumer<String, Throwable> onError) {
        this.onError = onError;
    }

    /** @return false when the writer is already stopped */
    public boolean submit(String errorKey, Task task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable error) {
                    onError.accept(errorKey, error);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** Waits for the writes already queued; false when it takes longer than the time. */
    public boolean drain(long timeoutMillis) {
        try {
            executor.submit(() -> { }).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | java.util.concurrent.ExecutionException | RejectedExecutionException e) {
            return false;
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
