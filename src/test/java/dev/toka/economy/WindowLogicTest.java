package dev.toka.economy;

import dev.toka.economy.command.CommandLogic;
import dev.toka.economy.command.Line;
import dev.toka.economy.command.Names;
import dev.toka.economy.core.EconomyService;
import dev.toka.economy.window.WindowLogic;
import dev.toka.economy.window.WindowSettings;
import dev.toka.i18n.Messages;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class WindowLogicTest {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private TestSupport.MovableClock clock;
    private EconomyService service;
    private CommandLogic logic;
    private WindowLogic windows;
    private Messages messages;

    @Before
    public void setUp() throws Exception {
        clock = new TestSupport.MovableClock("2026-10-17T12:00:00Z");
        service = TestSupport.service(TestSupport.settings(), new TestSupport.MemoryPersist(), clock, Set.of());
        service.createAccount(alice, "Alice");
        service.createAccount(bob, "Bob");
        Names names = new Names() {
            @Override
            public void resolve(String name, Consumer<Optional<UUID>> done) {
                done.accept(Optional.empty());
            }

            @Override
            public String display(UUID id) {
                return id.equals(alice) ? "Alice" : "Bob";
            }
        };
        messages = new Messages(getClass().getClassLoader(), Path.of("target", "no-lang-folder"), "zh-TW", w -> {
        });
        logic = new CommandLogic(service, names, messages);
        windows = new WindowLogic(service, logic);
    }

    @Test
    public void currenciesStartWithTheMainOne() {
        assertEquals(List.of("money", "coin"), windows.currencies());
    }

    @Test
    public void exchangePairsAreTheConfiguredRates() {
        assertTrue(windows.exchangeEnabled());
        List<String> pairs = windows.pairs().stream().map(p -> p.from() + ">" + p.to()).toList();
        assertEquals(List.of("money>coin", "coin>money"), pairs);
    }

    @Test
    public void noRatesMeansNoExchange() throws Exception {
        var settings = TestSupport.defaultConfig();
        settings.put("exchange", new HashMap<>());
        EconomyService none = TestSupport.service(dev.toka.economy.core.Settings.parse(settings), new TestSupport.MemoryPersist(), clock, Set.of());
        assertFalse(new WindowLogic(none, logic).exchangeEnabled());
    }

    @Test
    public void transferReviewShowsFeeTotalAndBalanceAfter() {
        service.deposit(alice, "money", 1000, Cause.API);
        WindowLogic.Review r = windows.transfer(alice, bob, "Bob", "money", " 500 ");
        assertTrue(r.ok());
        assertEquals(500, r.amount());
        assertEquals(10, r.fee());
        assertEquals(510, r.total());
        assertEquals(490, r.balance());
        // a review changes nothing
        assertEquals(1000, service.balance(alice, "money").balance());
        assertEquals(0, service.balance(bob, "money").balance());
    }

    @Test
    public void transferReviewNamesTheReason() {
        service.deposit(alice, "money", 100, Cause.API);
        assertEquals("err.amount", windows.transfer(alice, bob, "Bob", "money", "").error().key());
        assertEquals("err.amount", windows.transfer(alice, bob, "Bob", "money", "-3").error().key());
        assertEquals("err.amount", windows.transfer(alice, bob, "Bob", "money", "1.5").error().key());
        assertEquals("err.insufficient", windows.transfer(alice, bob, "Bob", "money", "100").error().key());
        assertEquals("err.self", windows.transfer(alice, alice, "Alice", "money", "5").error().key());
        assertEquals("err.currency", windows.transfer(alice, bob, "Bob", "gem", "5").error().key());
        assertEquals("err.noAccount", windows.transfer(alice, UUID.randomUUID(), "Ghost", "money", "5").error().key());
    }

    @Test
    public void exchangeReviewShowsWhatThePlayerGets() {
        service.deposit(alice, "money", 1000, Cause.API);
        WindowLogic.Pair pair = windows.pairs().get(0);
        WindowLogic.Review r = windows.exchange(alice, pair, "500");
        assertTrue(r.ok());
        assertEquals(5, r.received());
        assertEquals(500, r.balance());
        assertEquals(1000, service.balance(alice, "money").balance());
        // too small to give anything, and not enough money
        assertEquals("err.amount", windows.exchange(alice, pair, "50").error().key());
        assertEquals("err.insufficient", windows.exchange(alice, pair, "5000").error().key());
        assertEquals("err.amount", windows.exchange(alice, pair, "abc").error().key());
    }

    @Test
    public void boardMarksTheCallerAndIsEmptyWithoutBalances() {
        assertTrue(logic.board("money", 500, alice).rows().isEmpty());
        service.deposit(alice, "money", 100, Cause.API);
        service.deposit(bob, "money", 300, Cause.API);
        CommandLogic.Board board = logic.board("money", 500, alice);
        assertEquals(List.of("ui.top.row", "ui.top.self"), board.rows().stream().map(Line::key).toList());
        assertEquals(2, board.selfRank());
        assertEquals("Bob", board.rows().get(0).args()[0]);
        // beyond the limit: not listed
        assertEquals(0, logic.board("money", 1, alice).selfRank());
        assertEquals(1, logic.board("money", 1, alice).rows().size());
    }

    @Test
    public void ledgerRowsAreNewestFirstAndStartWithTheTime() {
        service.deposit(alice, "money", 1000, Cause.of("give", "admin"));
        clock.millis += 60_000;
        service.transfer(alice, bob, "money", 100, Cause.of("pay", "test"));
        List<CommandLogic.Row> rows = logic.ledgerRows(alice, 500);
        // the deposit, then the transfer and its fee (the account got a starting row only when initial > 0)
        assertTrue(rows.size() >= 3);
        assertEquals("ui.ledger.out", rows.get(0).line().key());
        String newest = String.valueOf(rows.get(0).line().args()[0]);
        String oldest = String.valueOf(rows.get(rows.size() - 1).line().args()[0]);
        assertTrue(newest.compareTo(oldest) > 0);
        assertTrue(newest.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        assertEquals("ui.ledger.in", rows.get(rows.size() - 1).line().key());
        // the rows carry the time and id the window sorts by
        assertTrue(rows.get(0).time() > rows.get(rows.size() - 1).time());
        assertTrue(rows.get(0).id() > rows.get(rows.size() - 1).id());
        assertEquals(2, logic.ledgerRows(alice, 2).size());
        assertEquals(1, logic.ledgerRows(bob, 500).size());
        // every row has a text in the language file
        for (CommandLogic.Row row : rows) {
            assertTrue(messages.text(row.line().key(), row.line().args()).contains("$"));
        }
    }

    @Test
    public void windowSettingsKeepToTheLimits() {
        WindowSettings d = WindowSettings.parse(Map.of());
        assertEquals(10, d.perPage());
        assertEquals(3, d.min());
        assertEquals(30, d.max());
        assertEquals(8, d.threshold());
        assertTrue(d.icons());
        assertEquals(500, d.rowsMax());
        WindowSettings s = WindowSettings.parse(Map.of("window-items-min", 1, "window-items-max", 2, "window-items-per-page", 99,
                "window-icons", false, "window-rows-max", 1, "window-search-threshold", "x"));
        assertEquals(3, s.min());
        assertEquals(3, s.max());
        assertEquals(3, s.perPage());
        assertFalse(s.icons());
        assertEquals(10, s.rowsMax());
        assertEquals(8, s.threshold());
    }

    @Test
    public void defaultConfigHasTheWindowKeys() throws Exception {
        Map<String, Object> config = TestSupport.defaultConfig();
        for (String key : List.of("window-items-per-page", "window-items-min", "window-items-max", "window-search-threshold", "window-icons", "window-rows-max")) {
            assertTrue(key, config.containsKey(key));
        }
        assertEquals(WindowSettings.parse(Map.of()), WindowSettings.parse(config));
    }
}
