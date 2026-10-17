package dev.toka.economy.storage;

import com.google.gson.JsonObject;
import dev.toka.economy.core.EconomyService;
import dev.toka.economy.core.Persist;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/** Saves batches into the SQLite document store from the writer thread, and runs the ledger clean-up there too. */
public final class DocPersist implements Persist, AutoCloseable {
    public static final int CLEAN_BATCH = 100;

    private final DocumentStore store;
    private final Writer writer;

    private DocPersist(DocumentStore store, Writer writer) {
        this.store = store;
        this.writer = writer;
    }

    /** Opens (and creates) the store. Reads the whole store: only for start-up. */
    public static DocPersist open(Path directory, BiConsumer<String, Throwable> onError) throws IOException {
        return new DocPersist(DocumentStore.open(new StoreConfig(Backend.SQLITE, directory, 1)), new Writer(onError));
    }

    /** Every document, read on the calling thread. Only for start-up. */
    public Map<String, JsonObject> snapshot() throws IOException {
        return store.snapshot();
    }

    @Override
    public void save(Map<String, JsonObject> documents) {
        Map<String, JsonObject> copy = Map.copyOf(documents);
        writer.submit("log.writeFailed", () -> store.saveAll(copy));
    }

    /** Deletes old ledger rows in small steps, each a separate write so that saves are not held up. */
    public CompletableFuture<Integer> cleanup(EconomyService service) {
        CompletableFuture<Integer> done = new CompletableFuture<>();
        step(service, done, 0);
        return done;
    }

    private void step(EconomyService service, CompletableFuture<Integer> done, int total) {
        boolean queued = writer.submit("log.cleanupFailed", () -> {
            try {
                List<String> keys = service.trim(CLEAN_BATCH);
                for (String key : keys) {
                    store.delete(key);
                }
                if (keys.size() >= CLEAN_BATCH) {
                    step(service, done, total + keys.size());
                } else {
                    done.complete(total + keys.size());
                }
            } catch (Throwable error) {
                done.completeExceptionally(error);
                if (error instanceof IOException io) {
                    throw io;
                }
                if (error instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IOException(error);
            }
        });
        if (!queued) {
            done.completeExceptionally(new IllegalStateException("writer stopped"));
        }
    }

    /** Waits for queued writes up to the time (false when it timed out), then closes the store. */
    public boolean closeAfterDrain(long timeoutMillis) throws IOException {
        boolean drained = writer.drain(timeoutMillis);
        writer.shutdown();
        if (drained) {
            store.close();
        }
        return drained;
    }

    @Override
    public void close() throws IOException {
        closeAfterDrain(10_000);
    }
}
