package dev.toka.economy;

import com.google.gson.JsonObject;
import dev.toka.economy.TestSupport.MemoryPersist;
import dev.toka.economy.TestSupport.MovableClock;
import dev.toka.economy.command.Caller;
import dev.toka.economy.command.CommandLogic;
import dev.toka.economy.command.Line;
import dev.toka.economy.command.Names;
import dev.toka.economy.core.Codec;
import dev.toka.economy.core.EconomyService;
import dev.toka.economy.core.EconomyService.Outcome;
import dev.toka.economy.core.Reasons;
import dev.toka.economy.core.Settings;
import dev.toka.economy.storage.DocPersist;
import dev.toka.i18n.Messages;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class LegacyTest {
    private static final Cause SOURCE = Cause.of("import", "oldSystem");
    private final UUID steve = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private MemoryPersist persist;
    private EconomyService service;
    private CommandLogic logic;
    private final Map<String, UUID> known = new HashMap<>();

    @SuppressWarnings("unchecked")
    private EconomyService make(MemoryPersist store, int initial) throws Exception {
        Map<String, Object> config = TestSupport.defaultConfig();
        Map<String, Object> currencies = (Map<String, Object>) config.get("currencies");
        for (Object currency : currencies.values()) {
            ((Map<String, Object>) currency).put("initial", initial);
        }
        return TestSupport.service(Settings.parse(config), store, new MovableClock("2026-10-17T12:00:00Z"), Set.of());
    }

    @Before
    public void setUp() throws Exception {
        persist = new MemoryPersist();
        service = make(persist, 0);
        Names names = new Names() {
            @Override
            public void resolve(String name, Consumer<Optional<UUID>> done) {
                done.accept(Optional.ofNullable(known.get(name.toLowerCase(Locale.ROOT))));
            }

            @Override
            public String display(UUID id) {
                return id.equals(steve) ? "Steve" : "Other";
            }
        };
        logic = new CommandLogic(service, names, new Messages(getClass().getClassLoader(), Path.of("target", "no-lang-folder"), "zh-TW", w -> { }));
        known.put("steve", steve);
    }

    private static List<String> keys(List<Line> lines) {
        return lines.stream().map(Line::key).toList();
    }

    private List<Line> legacy(String... args) {
        List<List<Line>> out = new ArrayList<>();
        logic.legacy(new Caller(null, "CONSOLE"), args, out::add);
        assertEquals(1, out.size());
        return out.get(0);
    }

    @Test
    public void importStoresADocumentAndReplacesTheSameNameInAnyCase() {
        assertTrue(service.importLegacy("Steve", Map.of("money", 500L, "coin", 0L), SOURCE).ok());
        assertEquals(1, service.legacyCount());
        JsonObject doc = persist.documents.get("legacy.n.steve");
        assertEquals("Steve", doc.get("name").getAsString());
        assertEquals("oldSystem", doc.get("source").getAsString());
        assertEquals(500, doc.getAsJsonObject("balances").get("money").getAsLong());
        assertFalse(doc.getAsJsonObject("balances").has("coin"));
        assertTrue(doc.get("time").getAsLong() > 0);

        assertTrue(service.importLegacy("STEVE", Map.of("money", 700L), SOURCE).ok());
        assertEquals(1, service.legacyCount());
        assertEquals(700, persist.documents.get("legacy.n.steve").getAsJsonObject("balances").get("money").getAsLong());
        assertEquals(1, persist.documents.keySet().stream().filter(Codec::isLegacyKey).count());
    }

    @Test
    public void importRejectsBadInput() {
        assertEquals(Status.INVALID_NAME, service.importLegacy("  ", Map.of("money", 1L), SOURCE).status());
        assertEquals(Status.INVALID_NAME, service.importLegacy("a".repeat(101), Map.of("money", 1L), SOURCE).status());
        assertEquals(Status.UNKNOWN_CURRENCY, service.importLegacy("x", Map.of("gem", 1L), SOURCE).status());
        assertEquals(Status.INVALID_AMOUNT, service.importLegacy("x", Map.of("money", -1L), SOURCE).status());
        assertEquals(Status.INVALID_AMOUNT, service.importLegacy("x", Map.of("money", 0L), SOURCE).status());
        assertEquals(0, service.legacyCount());
        assertTrue(persist.documents.isEmpty());
    }

    @Test
    public void namesWithOddCharactersGetAValidKey() {
        assertTrue(service.importLegacy("小明 Test", Map.of("money", 5L), SOURCE).ok());
        String key = persist.documents.keySet().iterator().next();
        assertTrue(key, key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"));
        assertTrue(Codec.isLegacyKey(key));
        assertFalse(Codec.isLedgerKey(key));
        assertEquals(Outcome.CLAIMED, service.join(steve, "小明 test").outcome());
    }

    @Test
    public void joiningWithTheSameNameClaimsItAndWritesTheLedger() throws Exception {
        service = make(persist, 100);
        service.importLegacy("Steve", Map.of("money", 500L, "coin", 3L), SOURCE);
        EconomyService.Join join = service.join(steve, "sTEVE");
        assertEquals(Outcome.CLAIMED, join.outcome());
        // the legacy balances replace the starting balances
        assertEquals(500, service.balance(steve, "money").balance());
        assertEquals(3, service.balance(steve, "coin").balance());
        assertEquals(0, service.legacyCount());
        assertTrue(persist.documents.keySet().stream().noneMatch(Codec::isLegacyKey));
        assertEquals(2, service.ledgerSize());
        List<String> reasons = persist.documents.entrySet().stream().filter(e -> Codec.isLedgerKey(e.getKey()))
                .map(e -> e.getValue().get("reason").getAsString()).toList();
        assertEquals(List.of(Reasons.LEGACY_CLAIM, Reasons.LEGACY_CLAIM), reasons);
        assertEquals(steve.toString(), persist.documents.get(Codec.ledgerKey(1)).get("to").getAsString());
        // nothing left to claim on the next join
        assertEquals(Outcome.NONE, service.join(steve, "Steve").outcome());
        assertEquals(500, service.balance(steve, "money").balance());
    }

    @Test
    public void anAccountWithOnlyZeroBalancesCanClaim() {
        service.createAccount(steve, "Steve");
        service.deposit(steve, "money", 10, SOURCE);
        service.withdraw(steve, "money", 10, SOURCE);
        service.importLegacy("steve", Map.of("money", 40L), SOURCE);
        assertEquals(Outcome.CLAIMED, service.join(steve, "Steve").outcome());
        assertEquals(40, service.balance(steve, "money").balance());
    }

    @Test
    public void anAccountWithABalanceKeepsTheLegacyAccount() {
        service.createAccount(steve, "Steve");
        service.deposit(steve, "money", 10, SOURCE);
        service.importLegacy("Steve", Map.of("money", 500L), SOURCE);
        assertEquals(Outcome.HELD_BALANCE, service.join(steve, "Steve").outcome());
        assertEquals(10, service.balance(steve, "money").balance());
        assertEquals(1, service.legacyCount());
        assertTrue(persist.documents.containsKey("legacy.n.steve"));
    }

    @Test
    public void anotherAccountWithTheSameNameIsADispute() {
        service.createAccount(other, "STEVE");
        service.importLegacy("Steve", Map.of("money", 500L), SOURCE);
        assertEquals(Outcome.HELD_CONFLICT, service.join(steve, "Steve").outcome());
        assertTrue(service.hasAccount(steve));
        assertEquals(0, service.balance(steve, "money").balance());
        assertEquals(1, service.legacyCount());
        // the dispute is settled by hand
        assertEquals(Status.OK, service.claimLegacy("steve", steve, null, Cause.of(Reasons.LEGACY_CLAIM, "admin:op")).status());
        assertEquals(500, service.balance(steve, "money").balance());
    }

    @Test
    public void otherNamesAreNotTouched() {
        service.importLegacy("Steve", Map.of("money", 500L), SOURCE);
        assertEquals(Outcome.NONE, service.join(other, "Alex").outcome());
        assertTrue(service.hasAccount(other));
        assertEquals(1, service.legacyCount());
    }

    @Test
    public void claimCommandForcesTheMergeAndAddsToTheBalance() {
        service.createAccount(steve, "Steve");
        service.deposit(steve, "money", 10, SOURCE);
        service.importLegacy("Alex", Map.of("money", 500L), SOURCE);
        List<Line> out = legacy("claim", "alex", "Steve");
        assertEquals(List.of("msg.legacy.claimed"), keys(out));
        assertEquals(510, service.balance(steve, "money").balance());
        assertEquals(0, service.legacyCount());
        assertFalse(persist.documents.containsKey("legacy.n.alex"));
        assertEquals(Reasons.LEGACY_CLAIM, persist.documents.get(Codec.ledgerKey(2)).get("reason").getAsString());
        assertEquals("admin:CONSOLE", persist.documents.get(Codec.ledgerKey(2)).get("source").getAsString());
    }

    @Test
    public void claimCommandReportsMissingNamesAndPlayers() {
        service.importLegacy("Alex", Map.of("money", 500L), SOURCE);
        assertEquals(List.of("err.legacy.notFound"), keys(legacy("claim", "nobody", "Steve")));
        assertEquals(List.of("err.player.notFound"), keys(legacy("claim", "alex", "ghost")));
        assertEquals(List.of("err.usage"), keys(legacy("claim", "alex")));
        assertEquals(1, service.legacyCount());
    }

    @Test
    public void claimWithAnOverflowChangesNothing() {
        service.createAccount(steve, "Steve");
        service.set(steve, "money", Long.MAX_VALUE - 1, SOURCE);
        service.importLegacy("Alex", Map.of("money", 5L), SOURCE);
        assertEquals(Status.OVERFLOW, service.claimLegacy("alex", steve, null, SOURCE).status());
        assertEquals(1, service.legacyCount());
        assertEquals(Long.MAX_VALUE - 1, service.balance(steve, "money").balance());
    }

    @Test
    public void dropRemovesOneAndListShowsTheRest() {
        service.importLegacy("Alex", Map.of("money", 500L), SOURCE);
        service.importLegacy("Bea", Map.of("coin", 7L), SOURCE);
        List<Line> list = legacy("list");
        assertEquals(List.of("msg.legacy.header", "msg.legacy.line", "msg.legacy.line"), keys(list));
        assertEquals("Alex", list.get(1).args()[0]);
        assertEquals(List.of("msg.legacy.dropped"), keys(legacy("drop", "ALEX")));
        assertEquals(1, service.legacyCount());
        assertFalse(persist.documents.containsKey("legacy.n.alex"));
        assertEquals(List.of("err.legacy.notFound"), keys(legacy("drop", "alex")));
        legacy("drop", "bea");
        assertEquals(List.of("msg.legacy.empty"), keys(legacy("list")));
        assertEquals(List.of("err.usage"), keys(legacy()));
    }

    @Test
    public void importRepeatedAfterAClaimMakesANewLegacyAccount() {
        service.importLegacy("Steve", Map.of("money", 500L), SOURCE);
        service.join(steve, "Steve");
        service.importLegacy("Steve", Map.of("money", 500L), SOURCE);
        assertEquals(1, service.legacyCount());
        assertEquals(Outcome.HELD_BALANCE, service.join(steve, "Steve").outcome());
    }

    @Test
    public void legacyAccountsSurviveARestartAndAClaimDeletesTheDocument() throws Exception {
        Path root = Path.of(".build-tools", "tests");
        Files.createDirectories(root);
        Path dir = Files.createTempDirectory(root, "store-").resolve("storage").resolve("sqlite");
        List<Throwable> errors = new ArrayList<>();
        DocPersist first = DocPersist.open(dir, (key, error) -> errors.add(error));
        EconomyService one = new EconomyService(TestSupport.settings(), first, Clock.systemUTC(), (id, node) -> false, first.snapshot());
        one.importLegacy("Steve", Map.of("money", 500L), SOURCE);
        one.importLegacy("小明", Map.of("coin", 2L), SOURCE);
        assertTrue(first.closeAfterDrain(10_000));

        DocPersist second = DocPersist.open(dir, (key, error) -> errors.add(error));
        EconomyService two = new EconomyService(TestSupport.settings(), second, Clock.systemUTC(), (id, node) -> false, second.snapshot());
        assertEquals(2, two.legacyCount());
        assertEquals(Outcome.CLAIMED, two.join(steve, "steve").outcome());
        assertTrue(two.dropLegacy("小明"));
        assertTrue(second.closeAfterDrain(10_000));

        DocPersist third = DocPersist.open(dir, (key, error) -> errors.add(error));
        EconomyService three = new EconomyService(TestSupport.settings(), third, Clock.systemUTC(), (id, node) -> false, third.snapshot());
        assertEquals(0, three.legacyCount());
        assertEquals(500, three.balance(steve, "money").balance());
        assertEquals(1, three.ledgerSize());
        third.closeAfterDrain(10_000);
        assertTrue(errors.toString(), errors.isEmpty());
    }
}
