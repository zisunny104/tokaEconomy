package dev.toka.economy;

import com.djrapitops.plan.extension.NotReadyException;
import com.djrapitops.plan.extension.extractor.ExtensionExtractor;
import dev.toka.economy.plan.*;
import dev.toka.i18n.Messages;
import org.junit.Test;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class PlanTest {
    @Test public void onlyExplicitCurrenciesAreDisclosedAndSnapshotIsImmutable() throws Exception {
        var service = TestSupport.service(TestSupport.settings(), new TestSupport.MemoryPersist(),
                new TestSupport.MovableClock("2026-10-20T00:00:00Z"), Set.of());
        UUID id = UUID.randomUUID(); service.createAccount(id, "tester");
        service.deposit(id, "money", 42, new Cause("test", "test"));
        Messages messages = new Messages(getClass().getClassLoader(), Files.createTempDirectory("plan-lang"), "zh-TW", s -> {});
        PlanSettings settings = PlanSettings.parse(Map.of("plan", Map.of("enabled", true, "account", true,
                "balances", Map.of("money", true, "coin", false, "missing", true))));
        PlanExtension extension = new PlanExtension(service, messages, settings);
        new ExtensionExtractor(extension).validateAnnotations();
        var snapshot = extension.snapshot(id);
        assertEquals(Map.of("money", 42L), snapshot.balances());
        service.deposit(id, "money", 8, new Cause("test", "test"));
        assertEquals(Long.valueOf(42), snapshot.balances().get("money"));
        try { snapshot.balances().put("coin", 5L); fail(); } catch (UnsupportedOperationException expected) {}
        assertFalse(extension.snapshot(UUID.randomUUID()).account());
        assertTrue(extension.snapshot(UUID.randomUUID()).balances().isEmpty());
        extension.close();
        try { extension.snapshot(id); fail(); } catch (NotReadyException expected) {}
    }
    @Test public void defaultsArePrivateAndConcurrentReadsDoNotCreateAccounts() throws Exception {
        assertFalse(PlanSettings.parse(Map.of()).enabled());
        assertTrue(PlanSettings.parse(Map.of()).balances().isEmpty());
        var service = TestSupport.service(TestSupport.settings(), new TestSupport.MemoryPersist(),
                new TestSupport.MovableClock("2026-10-20T00:00:00Z"), Set.of());
        PlanExtension extension = new PlanExtension(service, null, new PlanSettings(true, true, Set.of("money")));
        UUID id = UUID.randomUUID();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) futures.add(pool.submit(() -> assertFalse(extension.snapshot(id).account())));
            for (Future<?> future : futures) future.get();
        }
        assertFalse(service.hasAccount(id));
    }
}
