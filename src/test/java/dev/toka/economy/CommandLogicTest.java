package dev.toka.economy;

import dev.toka.economy.command.Caller;
import dev.toka.economy.command.CommandLogic;
import dev.toka.economy.command.Line;
import dev.toka.economy.command.Names;
import dev.toka.economy.core.EconomyService;
import dev.toka.economy.core.Settings;
import dev.toka.i18n.Messages;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class CommandLogicTest {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final Map<String, UUID> known = new HashMap<>();
    private EconomyService service;
    private CommandLogic logic;
    private final Caller aliceCaller = new Caller(alice, "Alice");
    private final Caller console = new Caller(null, "CONSOLE");

    @Before
    public void setUp() throws Exception {
        known.put("bob", bob);
        known.put("alice", alice);
        service = TestSupport.service(TestSupport.settings(), new TestSupport.MemoryPersist(), new TestSupport.MovableClock("2026-10-17T12:00:00Z"), Set.of());
        service.createAccount(alice, "Alice");
        service.createAccount(bob, "Bob");
        Names names = new Names() {
            @Override
            public void resolve(String name, Consumer<Optional<UUID>> done) {
                done.accept(Optional.ofNullable(known.get(name.toLowerCase(Locale.ROOT))));
            }

            @Override
            public String display(UUID id) {
                return id.equals(alice) ? "Alice" : id.equals(bob) ? "Bob" : "?";
            }
        };
        logic = new CommandLogic(service, names, new Messages(getClass().getClassLoader(), Path.of("target", "no-lang-folder"), "zh-TW", w -> { }));
    }

    private List<Line> run(java.util.function.BiConsumer<String[], Consumer<List<Line>>> command, String... args) {
        List<List<Line>> out = new ArrayList<>();
        command.accept(args, out::add);
        assertEquals(1, out.size());
        return out.get(0);
    }

    private static List<String> keys(List<Line> lines) {
        return lines.stream().map(Line::key).toList();
    }

    @Test
    public void amountsAreWholeNumbers() {
        assertEquals(1500, CommandLogic.parseAmount("1,500"));
        assertEquals(0, CommandLogic.parseAmount("0"));
        assertEquals(-1, CommandLogic.parseAmount("-5"));
        assertEquals(-1, CommandLogic.parseAmount("1.5"));
        assertEquals(-1, CommandLogic.parseAmount("abc"));
        assertEquals(-1, CommandLogic.parseAmount("99999999999999999999"));
    }

    @Test
    public void payMovesMoneyAndNotifiesTheReceiver() {
        service.deposit(alice, "money", 1000, Cause.API);
        List<Line> lines = run((a, d) -> logic.pay(aliceCaller, a, d), "Bob", "100");
        assertEquals(List.of("msg.pay.sentFee", "msg.pay.received"), keys(lines));
        assertEquals(bob, lines.get(1).to());
        assertEquals(100, service.balance(bob, "money").balance());
        assertEquals(890, service.balance(alice, "money").balance());
    }

    @Test
    public void payErrorsAreReportedByKey() {
        assertEquals(List.of("err.playerOnly"), keys(run((a, d) -> logic.pay(console, a, d), "Bob", "1")));
        assertEquals(List.of("err.usage"), keys(run((a, d) -> logic.pay(aliceCaller, a, d), "Bob")));
        assertEquals(List.of("err.amount"), keys(run((a, d) -> logic.pay(aliceCaller, a, d), "Bob", "x")));
        assertEquals(List.of("err.currency"), keys(run((a, d) -> logic.pay(aliceCaller, a, d), "Bob", "5", "gem")));
        assertEquals(List.of("err.player.notFound"), keys(run((a, d) -> logic.pay(aliceCaller, a, d), "Nobody", "5")));
        assertEquals(List.of("err.insufficient"), keys(run((a, d) -> logic.pay(aliceCaller, a, d), "Bob", "5")));
        assertEquals(List.of("err.self"), keys(run((a, d) -> logic.pay(aliceCaller, a, d), "Alice", "5")));
    }

    @Test
    public void balanceOfOthersNeedsThePermission() {
        service.deposit(bob, "money", 42, Cause.API);
        assertEquals(List.of("err.noSee"), keys(run((a, d) -> logic.balance(aliceCaller, false, a, d), "Bob")));
        List<Line> seen = run((a, d) -> logic.balance(aliceCaller, true, a, d), "Bob");
        assertEquals("msg.balance.other", seen.get(0).key());
        assertEquals(3, seen.size());
        assertEquals("$42", seen.get(1).args()[1]);
        // one's own balance is always allowed, with or without the name
        assertEquals("msg.balance.self", run((a, d) -> logic.balance(aliceCaller, false, a, d)).get(0).key());
        assertEquals("msg.balance.self", run((a, d) -> logic.balance(aliceCaller, false, a, d), "alice").get(0).key());
        // only one currency
        assertEquals(2, run((a, d) -> logic.balance(aliceCaller, true, a, d), "Bob", "coin").size());
    }

    @Test
    public void consoleWithoutAPlayerGetsTheUsage() {
        assertEquals(List.of("err.usage"), keys(run((a, d) -> logic.balance(console, true, a, d))));
        assertEquals("msg.balance.other", run((a, d) -> logic.balance(console, true, a, d), "Bob").get(0).key());
    }

    @Test
    public void giveTakeAndSetShareOneLogic() {
        assertEquals(List.of("msg.admin.give"), keys(run((a, d) -> logic.adjust("give", console, a, d), "Bob", "500", "coin")));
        assertEquals(500, service.balance(bob, "coin").balance());
        assertEquals(List.of("msg.admin.take"), keys(run((a, d) -> logic.adjust("take", console, a, d), "Bob", "200", "coin")));
        assertEquals(List.of("msg.admin.set"), keys(run((a, d) -> logic.adjust("set", console, a, d), "Bob", "0", "coin")));
        assertEquals(0, service.balance(bob, "coin").balance());
        assertEquals(List.of("err.amount"), keys(run((a, d) -> logic.adjust("give", console, a, d), "Bob", "0")));
        assertEquals(List.of("err.usage"), keys(run((a, d) -> logic.adjust("give", console, a, d), "Bob")));
        assertEquals(List.of("err.insufficient"), keys(run((a, d) -> logic.adjust("take", console, a, d), "Bob", "9")));
    }

    @Test
    public void exchangeSaysSoWhenThereAreNoRates() throws Exception {
        Map<String, Object> config = TestSupport.defaultConfig();
        ((Map<String, Object>) config.get("exchange")).put("rates", new LinkedHashMap<>());
        service.settings(Settings.parse(config));
        assertEquals(List.of("msg.exchange.none"), keys(run((a, d) -> logic.exchange(aliceCaller, a, d), "money", "coin", "100")));
        assertEquals(List.of("msg.exchange.none"), keys(run((a, d) -> logic.exchange(aliceCaller, a, d))));
        assertEquals(List.of("msg.exchange.none"), keys(logic.rates()));
    }

    @Test
    public void exchangeWorksAndListsRates() {
        service.deposit(alice, "money", 1000, Cause.API);
        assertEquals("msg.rates.header", run((a, d) -> logic.exchange(aliceCaller, a, d)).get(0).key());
        assertEquals(List.of("msg.exchange.done"), keys(run((a, d) -> logic.exchange(aliceCaller, a, d), "money", "coin", "500")));
        assertEquals(5, service.balance(alice, "coin").balance());
        assertEquals(List.of("err.usage"), keys(run((a, d) -> logic.exchange(aliceCaller, a, d), "money", "coin")));
        assertEquals(List.of("err.currency"), keys(run((a, d) -> logic.exchange(aliceCaller, a, d), "money", "gem", "5")));
    }

    @Test
    public void ledgerAndTopPage() {
        service.deposit(alice, "money", 10, Cause.API);
        List<Line> ledger = run((a, d) -> logic.ledger(a, d));
        assertEquals("msg.ledger.header", ledger.get(0).key());
        assertEquals("msg.ledger.line", ledger.get(1).key());
        assertEquals(List.of("err.player.notFound"), keys(run((a, d) -> logic.ledger(a, d), "Nobody")));
        assertEquals(List.of("msg.ledger.empty"), keys(run((a, d) -> logic.ledger(a, d), "Bob")));
        List<Line> top = logic.top(new String[0]);
        assertEquals("msg.top.header", top.get(0).key());
        assertEquals("msg.top.line", top.get(1).key());
        assertEquals(List.of("msg.top.header", "msg.top.empty"), keys(logic.top(new String[]{"coin"})));
        assertEquals(List.of("err.currency"), keys(logic.top(new String[]{"gem"})));
    }
}
