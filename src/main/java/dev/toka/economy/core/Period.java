package dev.toka.economy.core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/** The repeating period a count rule looks at. Weeks start on Monday, quarters on January, April, July and October. */
public enum Period {
    DAY, WEEK, MONTH, QUARTER;

    public static Optional<Period> parse(String text) {
        for (Period period : values()) {
            if (period.name().equalsIgnoreCase(text == null ? "" : text.strip())) {
                return Optional.of(period);
            }
        }
        return Optional.empty();
    }

    /** @return start (included) and end (excluded) of the period that has the moment in it */
    public LocalDateTime[] window(LocalDateTime now) {
        LocalDate day = now.toLocalDate();
        LocalDate start;
        LocalDate end;
        switch (this) {
            case DAY -> {
                start = day;
                end = day.plusDays(1);
            }
            case WEEK -> {
                start = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                end = start.plusWeeks(1);
            }
            case MONTH -> {
                start = day.withDayOfMonth(1);
                end = start.plusMonths(1);
            }
            default -> {
                start = LocalDate.of(day.getYear(), ((day.getMonthValue() - 1) / 3) * 3 + 1, 1);
                end = start.plusMonths(3);
            }
        }
        return new LocalDateTime[]{start.atStartOfDay(), end.atStartOfDay()};
    }
}
