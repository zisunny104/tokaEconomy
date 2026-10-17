package dev.toka.economy.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * One rule of fee-rules. A condition that is null (or empty) is not checked. Count conditions: the rule applies while
 * the sender has made fewer than {@code countLimit} transfers in the period (or between the two dates).
 */
public record FeeRule(String permission, long minAmount, long maxAmount, LocalTime timeFrom, LocalTime timeTo,
                      Set<DayOfWeek> days, LocalDate dateFrom, LocalDate dateTo,
                      int countLimit, Period countPeriod, LocalDate countFrom, LocalDate countTo,
                      int discount, boolean stack) {
    public static final long NO_MAX = Long.MAX_VALUE;

    public boolean hasCount() {
        return countLimit > 0;
    }
}
