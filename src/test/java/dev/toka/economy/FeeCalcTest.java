package dev.toka.economy;

import dev.toka.economy.core.Fee;
import dev.toka.economy.core.FeeCalc;
import dev.toka.economy.core.FeeRule;
import dev.toka.economy.core.Period;
import dev.toka.economy.core.Settings;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class FeeCalcTest {
    private static final LocalDateTime SATURDAY_NOON = LocalDateTime.of(2026, 10, 17, 12, 0); // a Saturday
    private static final Predicate<String> NONE = node -> false;
    private static final FeeCalc.Counter ZERO = (a, b) -> 0;

    private static List<FeeRule> rules(String yaml) {
        Map<String, Object> config = TestSupport.config("currencies:\n  money: {primary: true}\n" + yaml);
        return Settings.parse(config).fee().rules();
    }

    @Test
    public void baseFeeIsThePercentageRoundedDownWithAMinimum() {
        BigDecimal one = BigDecimal.ONE;
        assertEquals(10, FeeCalc.baseFee(one, 10, 500));
        assertEquals(50, FeeCalc.baseFee(one, 10, 5000));
        assertEquals(59, FeeCalc.baseFee(one, 10, 5999));
        assertEquals(0, FeeCalc.baseFee(BigDecimal.ZERO, 0, 5000));
        assertEquals(1, FeeCalc.baseFee(new BigDecimal("0.5"), 0, 250));
    }

    @Test
    public void discountsRoundTheFeeDown() {
        assertEquals(50, FeeCalc.applyDiscount(100, 50));
        assertEquals(0, FeeCalc.applyDiscount(100, 100));
        assertEquals(3, FeeCalc.applyDiscount(5, 30));
        assertEquals(100, FeeCalc.applyDiscount(100, 0));
    }

    @Test
    public void timeWindowsMayCrossMidnight() {
        LocalTime from = LocalTime.of(22, 0);
        LocalTime to = LocalTime.of(6, 0);
        assertTrue(FeeCalc.inTime(from, to, LocalTime.of(23, 30)));
        assertTrue(FeeCalc.inTime(from, to, LocalTime.of(5, 59)));
        assertFalse(FeeCalc.inTime(from, to, LocalTime.of(6, 0)));
        assertFalse(FeeCalc.inTime(from, to, LocalTime.of(12, 0)));
        assertTrue(FeeCalc.inTime(LocalTime.of(8, 0), LocalTime.of(9, 0), LocalTime.of(8, 0)));
        assertFalse(FeeCalc.inTime(LocalTime.of(8, 0), LocalTime.of(9, 0), LocalTime.of(9, 0)));
    }

    @Test
    public void permissionAmountDaysAndDatesAreChecked() {
        List<FeeRule> list = rules("fee-rules:\n  - permission: vip\n    min-amount: 100\n    max-amount: 1000\n    days: [sat, sun]\n    dates: {from: '2026-10-01', to: '2026-10-31'}\n    discount: 40\n");
        Predicate<String> vip = "vip"::equals;
        assertEquals(40, FeeCalc.discount(list, 500, vip, SATURDAY_NOON, ZERO));
        assertEquals(0, FeeCalc.discount(list, 500, NONE, SATURDAY_NOON, ZERO));
        assertEquals(0, FeeCalc.discount(list, 99, vip, SATURDAY_NOON, ZERO));
        assertEquals(0, FeeCalc.discount(list, 1001, vip, SATURDAY_NOON, ZERO));
        assertEquals(0, FeeCalc.discount(list, 500, vip, SATURDAY_NOON.plusDays(2), ZERO)); // Monday
        assertEquals(0, FeeCalc.discount(list, 500, vip, SATURDAY_NOON.plusDays(21), ZERO)); // Saturday in November
        assertEquals(40, FeeCalc.discount(list, 500, vip, LocalDateTime.of(2026, 10, 31, 23, 59), ZERO)); // last day is included
    }

    @Test
    public void bestRuleWinsUnlessStacked() {
        List<FeeRule> list = rules("fee-rules:\n  - discount: 30\n  - discount: 50\n  - free: false\n    discount: 20\n");
        assertEquals(50, FeeCalc.discount(list, 100, NONE, SATURDAY_NOON, ZERO));
        List<FeeRule> free = rules("fee-rules:\n  - discount: 30\n  - free: true\n");
        assertEquals(100, FeeCalc.discount(free, 100, NONE, SATURDAY_NOON, ZERO));
    }

    @Test
    public void stackedRulesAddUpToTheirCap() {
        List<FeeRule> list = rules("fee-rules:\n  - discount: 50\n  - discount: 30\n    stack: true\n  - discount: 15\n    stack: true\n");
        assertEquals(95, FeeCalc.discount(list, 100, NONE, SATURDAY_NOON, ZERO));
        List<FeeRule> over = rules("fee-rules:\n  - discount: 80\n  - discount: 40\n    stack: true\n");
        assertEquals(100, FeeCalc.discount(over, 100, NONE, SATURDAY_NOON, ZERO));
        List<FeeRule> onlyStack = rules("fee-rules:\n  - discount: 10\n    stack: true\n  - discount: 20\n    stack: true\n");
        assertEquals(30, FeeCalc.discount(onlyStack, 100, NONE, SATURDAY_NOON, ZERO));
    }

    @Test
    public void countRulesApplyWhileUnderTheLimit() {
        List<FeeRule> list = rules("fee-rules:\n  - count: {limit: 3, period: week}\n    free: true\n");
        assertEquals(100, FeeCalc.discount(list, 100, NONE, SATURDAY_NOON, (a, b) -> 2));
        assertEquals(0, FeeCalc.discount(list, 100, NONE, SATURDAY_NOON, (a, b) -> 3));
        // the week of Saturday 2026-10-17 starts on Monday 2026-10-12
        FeeCalc.discount(list, 100, NONE, SATURDAY_NOON, (a, b) -> {
            assertEquals(LocalDateTime.of(2026, 10, 12, 0, 0), a);
            assertEquals(LocalDateTime.of(2026, 10, 19, 0, 0), b);
            return 0;
        });
    }

    @Test
    public void countRulesWithDatesOnlyApplyInsideThem() {
        List<FeeRule> list = rules("fee-rules:\n  - count: {limit: 5, from: '2026-10-10', to: '2026-10-20'}\n    discount: 100\n");
        assertEquals(100, FeeCalc.discount(list, 100, NONE, SATURDAY_NOON, ZERO));
        assertEquals(0, FeeCalc.discount(list, 100, NONE, LocalDateTime.of(2026, 10, 21, 0, 0), ZERO));
        FeeCalc.discount(list, 100, NONE, SATURDAY_NOON, (a, b) -> {
            assertEquals(LocalDateTime.of(2026, 10, 10, 0, 0), a);
            assertEquals(LocalDateTime.of(2026, 10, 21, 0, 0), b);
            return 0;
        });
    }

    @Test
    public void periodWindows() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 15, 8, 30);
        assertEquals(LocalDateTime.of(2026, 11, 15, 0, 0), Period.DAY.window(now)[0]);
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), Period.MONTH.window(now)[0]);
        assertEquals(LocalDateTime.of(2026, 12, 1, 0, 0), Period.MONTH.window(now)[1]);
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), Period.QUARTER.window(now)[0]);
        assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0), Period.QUARTER.window(now)[1]);
        assertEquals(LocalDateTime.of(2026, 11, 9, 0, 0), Period.WEEK.window(now)[0]);
    }

    @Test
    public void wholeFeeWithExemptionAndRules() {
        Fee fee = new Fee(BigDecimal.ONE, 10, rules("fee-rules:\n  - discount: 50\n"));
        assertEquals(500, FeeCalc.fee(fee, 10, 100_000, false, NONE, SATURDAY_NOON, ZERO) * 1); // 1000 base, half
        assertEquals(0, FeeCalc.fee(fee, 10, 100_000, true, NONE, SATURDAY_NOON, ZERO));
        assertEquals(5, FeeCalc.fee(fee, 10, 100, false, NONE, SATURDAY_NOON, ZERO));
        assertEquals(0, FeeCalc.fee(new Fee(BigDecimal.ZERO, 0, List.of()), 0, 100, false, NONE, SATURDAY_NOON, ZERO));
        assertNotNull(Set.of());
    }

    @Test
    public void badRulesAreRefused() {
        for (String bad : List.of(
                "fee-rules:\n  - permission: x\n",
                "fee-rules:\n  - discount: 150\n",
                "fee-rules:\n  - discount: 10\n    time: '25:00-26:00'\n",
                "fee-rules:\n  - discount: 10\n    days: [funday]\n",
                "fee-rules:\n  - discount: 10\n    count: {limit: 2}\n",
                "fee-rules:\n  - discount: 10\n    count: {limit: 2, period: year}\n",
                "fee-rules:\n  - discount: 10\n    colour: red\n",
                "fee-rules:\n  - discount: 10\n    dates: {from: '2026-12-31', to: '2026-01-01'}\n")) {
            try {
                rules(bad);
                fail("accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }
}
