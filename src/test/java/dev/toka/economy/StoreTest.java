package dev.toka.economy;

import dev.toka.economy.core.EconomyService;
import dev.toka.economy.storage.DocPersist;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** The real SQLite store, written by the background thread. */
public class StoreTest {
    private static final Cause CAUSE = Cause.of("test", "StoreTest");

    private Path folder() throws Exception {
        Path root = Path.of(".build-tools", "tests");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "store-").resolve("storage").resolve("sqlite");
    }

    @Test
    public void balancesAndLedgerSurviveARestart() throws Exception {
        Path dir = folder();
        List<Throwable> errors = new ArrayList<>();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        DocPersist first = DocPersist.open(dir, (key, error) -> errors.add(error));
        EconomyService one = new EconomyService(TestSupport.settings(), first, Clock.systemUTC(), (id, node) -> false, first.snapshot());
        one.createAccount(a, "Alice");
        one.createAccount(b, "Bob");
        one.deposit(a, "money", 5000, CAUSE);
        one.transfer(a, b, "money", 1000, CAUSE);
        one.exchange(a, "money", "coin", 1000, CAUSE);
        assertTrue(first.closeAfterDrain(10_000));
        assertTrue(errors.toString(), errors.isEmpty());
        assertTrue(Files.exists(dir.resolve("documents.sqlite")));

        DocPersist second = DocPersist.open(dir, (key, error) -> errors.add(error));
        EconomyService two = new EconomyService(TestSupport.settings(), second, Clock.systemUTC(), (id, node) -> false, second.snapshot());
        assertEquals(5000 - 1010 - 1000, two.balance(a, "money").balance());
        assertEquals(10, two.balance(a, "coin").balance());
        assertEquals(1000, two.balance(b, "money").balance());
        assertEquals(5, two.ledgerSize()); // deposit, transfer, fee, exchange out, exchange in
        second.closeAfterDrain(10_000);
    }

    @Test
    public void cleanupDeletesInBatchesInTheBackground() throws Exception {
        Path dir = folder();
        UUID a = UUID.randomUUID();
        TestSupport.MovableClock clock = new TestSupport.MovableClock("2026-01-01T00:00:00Z");
        DocPersist persist = DocPersist.open(dir, (key, error) -> fail(key));
        EconomyService service = new EconomyService(TestSupport.settings(), persist, clock, (id, node) -> false, persist.snapshot());
        service.createAccount(a, "Alice");
        int rows = DocPersist.CLEAN_BATCH * 2 + 30;
        for (int i = 0; i < rows; i++) {
            service.deposit(a, "money", 1, CAUSE);
        }
        clock.millis += 400L * 86_400_000L; // all rows are older than 365 days
        service.deposit(a, "money", 1, CAUSE); // one fresh row
        int removed = persist.cleanup(service).get(30, TimeUnit.SECONDS);
        assertEquals(rows, removed);
        assertEquals(1, service.ledgerSize());
        assertEquals(rows + 1, service.balance(a, "money").balance());
        assertTrue(persist.closeAfterDrain(10_000));

        DocPersist again = DocPersist.open(dir, (key, error) -> fail(key));
        EconomyService reloaded = new EconomyService(TestSupport.settings(), again, clock, (id, node) -> false, again.snapshot());
        assertEquals(1, reloaded.ledgerSize());
        assertEquals(rows + 1, reloaded.balance(a, "money").balance());
        again.closeAfterDrain(10_000);
    }

    @Test
    public void savingAfterTheWriterStoppedDoesNotThrow() throws Exception {
        DocPersist persist = DocPersist.open(folder(), (key, error) -> { });
        EconomyService service = new EconomyService(TestSupport.settings(), persist, Clock.systemUTC(), (id, node) -> false, persist.snapshot());
        persist.closeAfterDrain(5000);
        service.createAccount(UUID.randomUUID(), "Late");
    }
}
