package dev.toka.economy.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Predicate;

/** The fee arithmetic and rule matching, as plain functions with no server or clock in them. */
public final class FeeCalc {
    /** Counts the transfers the sender made from the first moment (included) to the second (excluded). */
    @FunctionalInterface
    public interface Counter {
        int count(LocalDateTime from, LocalDateTime to);
    }

    private FeeCalc() {
    }

    /** Base fee: the percentage of the amount rounded down, but at least the minimum. */
    public static long baseFee(BigDecimal ratePercent, long min, long amount) {
        long byRate = BigDecimal.valueOf(amount).multiply(ratePercent).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).longValueExact();
        return Math.max(min, byRate);
    }

    /** @param discount percent from 0 to 100 */
    public static long applyDiscount(long fee, int discount) {
        int percent = Math.max(0, Math.min(100, discount));
        return BigDecimal.valueOf(fee).multiply(BigDecimal.valueOf(100 - percent)).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).longValueExact();
    }

    /** @param exempt true for a sender who has the free-fee permission */
    public static long fee(Fee fee, long min, long amount, boolean exempt, Predicate<String> permission, LocalDateTime now, Counter counter) {
        if (exempt) {
            return 0;
        }
        long base = baseFee(fee.ratePercent(), min, amount);
        if (base == 0) {
            return 0;
        }
        return applyDiscount(base, discount(fee.rules(), amount, permission, now, counter));
    }

    /** The best matching rule counts; rules with stack add their discounts on top of it. The total is at most 100. */
    public static int discount(List<FeeRule> rules, long amount, Predicate<String> permission, LocalDateTime now, Counter counter) {
        int best = 0;
        int stacked = 0;
        for (FeeRule rule : rules) {
            if (!matches(rule, amount, permission, now, counter)) {
                continue;
            }
            if (rule.stack()) {
                stacked += rule.discount();
            } else {
                best = Math.max(best, rule.discount());
            }
        }
        return Math.min(100, best + stacked);
    }

    public static boolean matches(FeeRule rule, long amount, Predicate<String> permission, LocalDateTime now, Counter counter) {
        if (rule.permission() != null && !permission.test(rule.permission())) {
            return false;
        }
        if (amount < rule.minAmount() || amount > rule.maxAmount()) {
            return false;
        }
        if (rule.timeFrom() != null && !inTime(rule.timeFrom(), rule.timeTo(), now.toLocalTime())) {
            return false;
        }
        if (rule.days() != null && !rule.days().isEmpty() && !rule.days().contains(now.getDayOfWeek())) {
            return false;
        }
        if (!inDates(rule.dateFrom(), rule.dateTo(), now.toLocalDate())) {
            return false;
        }
        if (rule.hasCount()) {
            LocalDateTime from;
            LocalDateTime to;
            if (rule.countPeriod() != null) {
                LocalDateTime[] window = rule.countPeriod().window(now);
                from = window[0];
                to = window[1];
            } else {
                if (!inDates(rule.countFrom(), rule.countTo(), now.toLocalDate())) {
                    return false;
                }
                from = rule.countFrom() == null ? LocalDateTime.MIN : rule.countFrom().atStartOfDay();
                to = rule.countTo() == null ? LocalDateTime.MAX : rule.countTo().plusDays(1).atStartOfDay();
            }
            return counter.count(from, to) < rule.countLimit();
        }
        return true;
    }

    /** From is included, to is excluded; from after to means the period goes over midnight. */
    public static boolean inTime(LocalTime from, LocalTime to, LocalTime now) {
        if (from.equals(to)) {
            return true;
        }
        if (from.isBefore(to)) {
            return !now.isBefore(from) && now.isBefore(to);
        }
        return !now.isBefore(from) || now.isBefore(to);
    }

    /** Both dates are included; a null side is open. */
    public static boolean inDates(LocalDate from, LocalDate to, LocalDate day) {
        return (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to));
    }
}
