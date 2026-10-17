package dev.toka.economy;

import dev.toka.economy.core.Settings;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SettingsTest {
    @Test
    public void theDefaultConfigIsValid() throws Exception {
        Settings s = TestSupport.settings();
        assertEquals("money", s.primary());
        assertEquals(List.of("money", "coin"), List.copyOf(s.currencies().keySet()));
        assertEquals("$", s.currencies().get("money").symbol());
        assertEquals("金幣", s.currencies().get("coin").names().get("zh-TW"));
        assertEquals(10, s.fee().min());
        assertEquals(0, s.fee().rules().size());
        assertEquals(365, s.keepDays());
        assertEquals(200000, s.maxRows());
        assertTrue(s.compatCommands());
        assertEquals(2, s.rates().size());
    }

    @Test
    public void exactlyOnePrimaryCurrencyIsRequired() {
        assertBad("currencies:\n  money: {symbol: '$'}\n");
        assertBad("currencies:\n  money: {primary: true}\n  coin: {primary: true}\n");
        assertBad("currencies: {}\n");
    }

    @Test
    public void currencyCodesAreLowercaseAndShort() {
        assertBad("currencies:\n  Money: {primary: true}\n");
        assertBad("currencies:\n  m: {primary: true}\n");
        assertBad("currencies:\n  this-is-way-too-long-x: {primary: true}\n");
        assertBad("currencies:\n  'a b': {primary: true}\n");
        assertEquals("ab12", Settings.parse(TestSupport.config("currencies:\n  ab12: {primary: true}\n")).primary());
    }

    @Test
    public void namesMayBeEmptyAndFallBackToTheCode() {
        Settings s = Settings.parse(TestSupport.config("currencies:\n  money: {primary: true, names: {}}\n"));
        assertTrue(s.currencies().get("money").names().isEmpty());
    }

    @Test
    public void exchangeRatesMustUseKnownCurrenciesAndBePositive() {
        String base = "currencies:\n  money: {primary: true}\n  coin: {}\n";
        assertBad(base + "exchange:\n  rates:\n    money: {gem: 2}\n");
        assertBad(base + "exchange:\n  rates:\n    money: {money: 2}\n");
        assertBad(base + "exchange:\n  rates:\n    money: {coin: 0}\n");
        assertBad(base + "exchange:\n  rates:\n    money: {coin: abc}\n");
        assertEquals(1, Settings.parse(TestSupport.config(base + "exchange:\n  rates:\n    money: {coin: 0.5}\n")).rates().size());
        assertTrue(Settings.parse(TestSupport.config(base)).rates().isEmpty());
    }

    @Test
    public void numbersAreChecked() {
        String base = "currencies:\n  money: {primary: true}\n";
        assertBad(base + "fee: {rate: 101}\n");
        assertBad(base + "fee: {min: -1}\n");
        assertBad(base + "ledger: {keep-days: -1}\n");
        assertBad(base + "ledger: {max-rows: 1.5}\n");
        assertEquals(0, Settings.parse(TestSupport.config(base + "ledger: {keep-days: 0, max-rows: 0}\n")).keepDays());
    }

    @Test
    public void feeMinCanBeSetPerCurrency() {
        Settings s = Settings.parse(TestSupport.config("fee: {min: 10}\ncurrencies:\n  money: {primary: true}\n  coin: {fee-min: 1}\n"));
        assertEquals(10, s.feeMin("money"));
        assertEquals(1, s.feeMin("coin"));
    }

    private static void assertBad(String yaml) {
        try {
            Settings.parse(TestSupport.config(yaml));
            fail("accepted: " + yaml);
        } catch (IllegalArgumentException expected) {
            assertNotNull(Map.of("m", expected.getMessage()));
        }
    }
}
