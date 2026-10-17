package dev.toka.economy;

import dev.toka.economy.TestSupport.MemoryPersist;
import dev.toka.economy.TestSupport.MovableClock;
import dev.toka.economy.core.EconomyService;
import dev.toka.economy.core.Settings;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

public class EconomyServiceTest {
    private static final Cause CAUSE = Cause.of("test", "tokaEconomyTest");
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private MemoryPersist persist;
    private MovableClock clock;
    private EconomyService service;

    @Before
    public void setUp() throws Exception {
        persist = new MemoryPersist();
        clock = new MovableClock("2026-10-17T12:00:00Z");
        service = TestSupport.service(TestSupport.settings(), persist, clock, Set.of());
        service.createAccount(alice, "Alice");
        service.createAccount(bob, "Bob");
    }

    @Test
    public void depositWithdrawAndSetChangeTheBalance() {
        assertEquals(1000, service.deposit(alice, "money", 1000, CAUSE).balance());
        Result w = service.withdraw(alice, "money", 400, CAUSE);
        assertTrue(w.ok());
        assertEquals(600, w.balance());
        assertEquals(600, service.balance(alice, "money").balance());
        assertEquals(50, service.set(alice, "money", 50, CAUSE).balance());
        assertEquals(Status.OK, service.has(alice, "money", 50).status());
        assertEquals(Status.INSUFFICIENT_FUNDS, service.has(alice, "money", 51).status());
    }

    @Test
    public void badInputIsRejectedWithoutChange() {
        service.deposit(alice, "money", 100, CAUSE);
        assertEquals(Status.INVALID_AMOUNT, service.deposit(alice, "money", 0, CAUSE).status());
        assertEquals(Status.INVALID_AMOUNT, service.withdraw(alice, "money", -5, CAUSE).status());
        assertEquals(Status.INVALID_AMOUNT, service.set(alice, "money", -1, CAUSE).status());
        assertEquals(Status.INSUFFICIENT_FUNDS, service.withdraw(alice, "money", 101, CAUSE).status());
        assertEquals(Status.NO_ACCOUNT, service.deposit(UUID.randomUUID(), "money", 1, CAUSE).status());
        assertEquals(100, service.balance(alice, "money").balance());
    }

    @Test
    public void onlyCurrenciesInTheConfigAreValid() {
        assertEquals(Status.UNKNOWN_CURRENCY, service.deposit(alice, "gem", 10, CAUSE).status());
        assertEquals(Status.UNKNOWN_CURRENCY, service.balance(alice, "").status());
        assertEquals(Status.UNKNOWN_CURRENCY, service.deposit(alice, null, 10, CAUSE).status());
        assertTrue(service.deposit(alice, "COIN", 10, CAUSE).ok());
        assertEquals("money", service.primary());
        assertEquals(List.of("money", "coin"), service.currencies());
    }

    @Test
    public void overflowIsRefused() {
        service.deposit(alice, "money", Long.MAX_VALUE - 5, CAUSE);
        Result r = service.deposit(alice, "money", 6, CAUSE);
        assertEquals(Status.OVERFLOW, r.status());
        assertEquals(Long.MAX_VALUE - 5, service.balance(alice, "money").balance());
    }

    @Test
    public void transferPaysTheFeeOnTopAndRecordsIt() {
        service.deposit(alice, "money", 2000, CAUSE);
        Result r = service.transfer(alice, bob, "money", 1000, CAUSE);
        assertTrue(r.ok());
        assertEquals(10, r.fee());          // 1% of 1000 = 10, minimum 10
        assertEquals(1000, r.received());
        assertEquals(990, service.balance(alice, "money").balance());
        assertEquals(1000, service.balance(bob, "money").balance());
        // deposit, transfer, fee
        assertEquals(3, service.ledgerSize());
    }

    @Test
    public void smallTransfersPayTheMinimumFee() {
        service.deposit(alice, "money", 100, CAUSE);
        Result r = service.transfer(alice, bob, "money", 50, CAUSE);
        assertEquals(10, r.fee());
        assertEquals(40, r.balance());
    }

    @Test
    public void aFailedTransferChangesNothing() {
        service.deposit(alice, "money", 105, CAUSE);
        service.deposit(bob, "money", Long.MAX_VALUE - 50, CAUSE);
        int rows = service.ledgerSize();
        int batches = persist.batches;

        Result needsFee = service.transfer(alice, bob, "money", 100, CAUSE); // 100 + fee 10 > 105
        assertEquals(Status.INSUFFICIENT_FUNDS, needsFee.status());
        assertEquals(110, needsFee.amount());
        Result overflow = service.transfer(alice, bob, "money", 100, CAUSE);
        assertNotEquals(Status.OK, overflow.status());
        assertEquals(Status.SAME_ACCOUNT, service.transfer(alice, alice, "money", 1, CAUSE).status());
        assertEquals(Status.NO_ACCOUNT, service.transfer(alice, UUID.randomUUID(), "money", 1, CAUSE).status());
        assertEquals(Status.INVALID_AMOUNT, service.transfer(alice, bob, "money", 0, CAUSE).status());

        assertEquals(105, service.balance(alice, "money").balance());
        assertEquals(Long.MAX_VALUE - 50, service.balance(bob, "money").balance());
        assertEquals(rows, service.ledgerSize());
        assertEquals(batches, persist.batches);
    }

    @Test
    public void aTransferIsSavedAsOneBatchWithBothAccounts() {
        service.deposit(alice, "money", 500, CAUSE);
        int before = persist.batches;
        service.transfer(alice, bob, "money", 100, CAUSE);
        assertEquals(before + 1, persist.batches);
        assertEquals(500 - 110, persist.documents.get("a" + alice).getAsJsonObject("balances").get("money").getAsLong());
        assertEquals(100, persist.documents.get("a" + bob).getAsJsonObject("balances").get("money").getAsLong());
    }

    @Test
    public void previewDoesNotChangeAnything() {
        service.deposit(alice, "money", 500, CAUSE);
        int batches = persist.batches;
        Result p = service.previewTransfer(alice, bob, "money", 100);
        assertTrue(p.ok());
        assertEquals(10, p.fee());
        assertEquals(500, service.balance(alice, "money").balance());
        assertEquals(batches, persist.batches);
    }

    @Test
    public void thePermissionMakesTransfersFree() throws Exception {
        EconomyService free = TestSupport.service(TestSupport.settings(), persist, clock, Set.of(EconomyService.FREE_PERMISSION));
        free.createAccount(alice, "Alice");
        free.createAccount(bob, "Bob");
        free.deposit(alice, "money", 200, CAUSE);
        assertEquals(0, free.transfer(alice, bob, "money", 100, CAUSE).fee());
        assertEquals(100, free.balance(alice, "money").balance());
    }

    @Test
    public void countRulesUseTheLedger() {
        Map<String, Object> config = rulesConfig("fee-rules:\n  - count: {limit: 2, period: day}\n    free: true\n");
        EconomyService s = new EconomyService(Settings.parse(config), persist, clock, (id, node) -> false, Map.of());
        s.createAccount(alice, "Alice");
        s.createAccount(bob, "Bob");
        s.deposit(alice, "money", 10_000, CAUSE);
        assertEquals(0, s.transfer(alice, bob, "money", 100, CAUSE).fee());
        assertEquals(0, s.transfer(alice, bob, "money", 100, CAUSE).fee());
        assertEquals(10, s.transfer(alice, bob, "money", 100, CAUSE).fee());
        clock.millis += 86_400_000L; // next day
        assertEquals(0, s.transfer(alice, bob, "money", 100, CAUSE).fee());
    }

    @Test
    public void exchangeRoundsDownAndWritesTheLedger() {
        service.deposit(alice, "money", 1000, CAUSE);
        int rows = service.ledgerSize();
        Result p = service.previewExchange(alice, "money", "coin", 155); // 155 * 0.01 = 1.55 -> 1
        assertEquals(1, p.received());
        assertEquals(rows, service.ledgerSize());
        Result r = service.exchange(alice, "money", "coin", 155, CAUSE);
        assertTrue(r.ok());
        assertEquals(1, r.received());
        assertEquals(845, service.balance(alice, "money").balance());
        assertEquals(1, service.balance(alice, "coin").balance());
        assertEquals(rows + 2, service.ledgerSize());
        assertEquals(Status.INVALID_AMOUNT, service.exchange(alice, "money", "coin", 99, CAUSE).status());
        assertEquals(Status.NO_RATE, service.exchange(alice, "money", "money", 10, CAUSE).status());
        assertEquals(Status.INSUFFICIENT_FUNDS, service.exchange(alice, "money", "coin", 100_000, CAUSE).status());
        assertEquals(90, EconomyService.convert(new java.math.BigDecimal("90"), 1));
        assertEquals(0, EconomyService.convert(new java.math.BigDecimal("0.999"), 1));
    }

    @Test
    public void exchangeRatesFollowReload() throws Exception {
        service.deposit(alice, "coin", 10, CAUSE);
        assertEquals(900, service.exchange(alice, "coin", "money", 10, CAUSE).received());
        Map<String, Object> config = TestSupport.defaultConfig();
        ((Map<String, Object>) ((Map<String, Object>) config.get("exchange")).get("rates")).put("coin", Map.of("money", 100));
        service.settings(Settings.parse(config));
        service.deposit(alice, "coin", 10, CAUSE);
        assertEquals(1000, service.exchange(alice, "coin", "money", 10, CAUSE).received());
    }

    @Test
    public void formatUsesThousandsSeparators() {
        assertEquals("12,345", service.format(12345, "money", false));
        assertEquals("$12,345", service.format(12345, "money", true));
        assertEquals("-$1,000", service.format(-1000, "money", true));
        assertEquals("1,000", service.format(1000, "nope", true));
        assertEquals("銀幣", service.currencyName("money", "zh-TW"));
        assertEquals("Silver", service.currencyName("money", "en-US"));
        assertEquals("銀幣", service.currencyName("money", "fr-FR"));
        assertEquals("money", service.currencyName("money", null).equals("銀幣") ? "money" : "x");
    }

    @Test
    public void topListsTheRichestFirst() {
        UUID carol = UUID.randomUUID();
        service.createAccount(carol, "Carol");
        service.deposit(alice, "money", 50, CAUSE);
        service.deposit(bob, "money", 300, CAUSE);
        service.deposit(carol, "money", 300, CAUSE);
        EconomyService.TopPage page = service.top("money", 1, 10);
        assertEquals(3, page.total());
        assertEquals("Bob", page.entries().get(0).name());
        assertEquals("Carol", page.entries().get(1).name());
        assertEquals(3, page.entries().get(2).rank());
        assertEquals(0, service.top("coin", 1, 10).total());
    }

    @Test
    public void everythingSurvivesAReload() throws Exception {
        service.deposit(alice, "money", 700, CAUSE);
        service.transfer(alice, bob, "money", 100, CAUSE);
        EconomyService again = new EconomyService(TestSupport.settings(), new MemoryPersist(), clock, (id, node) -> false, persist.documents);
        assertEquals(590, again.balance(alice, "money").balance());
        assertEquals(100, again.balance(bob, "money").balance());
        assertEquals(service.ledgerSize(), again.ledgerSize());
        assertEquals(alice, again.findByName("alice").orElseThrow());
        again.deposit(alice, "money", 1, CAUSE);
        assertEquals(service.ledgerSize() + 1, again.ledgerSize());
    }

    @Test
    public void trimRemovesOldRowsAndRespectsTheLimits() throws Exception {
        for (int i = 0; i < 10; i++) {
            service.deposit(alice, "money", 1, CAUSE);
            clock.millis += 86_400_000L;
        }
        // default keep is 365 days / 200000 rows: nothing to remove
        assertTrue(service.trim(100).isEmpty());
        Map<String, Object> config = TestSupport.defaultConfig();
        ((Map<String, Object>) config.get("ledger")).put("keep-days", 5);
        service.settings(Settings.parse(config));
        List<String> removed = service.trim(3);
        assertEquals(3, removed.size());
        assertEquals(7, service.ledgerSize());
        assertEquals("l000000000001", removed.get(0));
        assertTrue(service.trim(100).size() > 0);
        assertTrue(service.ledgerSize() <= 5);
        assertEquals(10, service.balance(alice, "money").balance()); // balances stay

        ((Map<String, Object>) config.get("ledger")).put("keep-days", 0);
        ((Map<String, Object>) config.get("ledger")).put("max-rows", 2);
        service.settings(Settings.parse(config));
        service.deposit(alice, "money", 1, CAUSE);
        service.deposit(alice, "money", 1, CAUSE);
        service.deposit(alice, "money", 1, CAUSE);
        service.trim(100);
        assertEquals(2, service.ledgerSize());
        ((Map<String, Object>) config.get("ledger")).put("max-rows", 0);
        service.settings(Settings.parse(config));
        assertTrue(service.trim(100).isEmpty());
    }

    private static Map<String, Object> rulesConfig(String rules) {
        try {
            Map<String, Object> config = TestSupport.defaultConfig();
            config.put("fee-rules", TestSupport.config(rules).get("fee-rules"));
            return config;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
