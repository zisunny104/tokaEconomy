package dev.toka.economy;

import dev.toka.economy.core.EconomyService;
import dev.toka.economy.core.Settings;
import dev.toka.i18n.Messages;
import org.junit.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

public class SidebarTest {
    private final Messages messages = new Messages(getClass().getClassLoader(), Path.of("target", "no-lang-folder"), "zh-TW", new ArrayList<String>()::add);

    private EconomyService service(String yaml) throws Exception {
        var root = yaml == null ? TestSupport.defaultConfig() : TestSupport.config(yaml);
        return TestSupport.service(Settings.parse(root), new TestSupport.MemoryPersist(), Clock.systemUTC(), Set.of());
    }

    @Test
    public void linesHaveNameColorAndThousandsSeparators() throws Exception {
        EconomyService s = service(null);
        UUID id = UUID.randomUUID();
        s.createAccount(id, "Steve");
        s.set(id, "money", 1234567, dev.toka.economy.Cause.of("t", "test"));
        s.set(id, "coin", 8, dev.toka.economy.Cause.of("t", "test"));
        assertEquals("§b[銀幣]1,234,567§r", Sidebar.line(s, messages, "money", id));
        assertEquals("§e[金幣]8§r", Sidebar.line(s, messages, "coin", id));
    }

    @Test
    public void nameFallsBackToDefaultLanguageThenCode() throws Exception {
        EconomyService s = service("language: ja-JP\ncurrencies:\n  money: {primary: true, names: {ja-JP: 銀}}\n  gem: {names: {en-US: Gem}}\n  xp: {}\n");
        UUID id = UUID.randomUUID();
        s.createAccount(id, "Steve");
        assertEquals("§b[銀]0§r", Sidebar.line(s, messages, "money", id));
        assertEquals("§a[gem]0§r", Sidebar.line(s, messages, "gem", id));
        assertEquals("§a[xp]0§r", Sidebar.line(s, messages, "xp", id));
    }

    @Test
    public void noAccountOrUnknownCurrencyGivesNull() throws Exception {
        EconomyService s = service(null);
        assertNull(Sidebar.line(s, messages, "money", UUID.randomUUID()));
        UUID id = UUID.randomUUID();
        s.createAccount(id, "Steve");
        assertNull(Sidebar.line(s, messages, "nope", id));
        assertNull(Sidebar.line(null, messages, "money", id));
    }

    @Test
    public void showDefaultsToTrue() throws Exception {
        assertTrue(Sidebar.show(TestSupport.config("language: zh-TW\n")));
        assertTrue(Sidebar.show(TestSupport.config("sidebar:\n  show: true\n")));
        assertFalse(Sidebar.show(TestSupport.config("sidebar:\n  show: false\n")));
        assertTrue(Sidebar.show(TestSupport.defaultConfig()));
    }
}
