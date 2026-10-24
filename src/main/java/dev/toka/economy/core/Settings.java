package dev.toka.economy.core;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;

/** The parsed and checked config.yml. A bad file throws {@link IllegalArgumentException} with the reason. */
public record Settings(String language, int helpLines, int topPerPage, boolean compatCommands, Map<String, Currency> currencies, String primary, Fee fee,
                       Map<String, Map<String, BigDecimal>> rates, int keepDays, int maxRows) {
    public static final Pattern CODE = Pattern.compile("[a-z0-9]{2,16}");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})");

    public static Settings parse(Map<?, ?> root) {
        String language = text(root.get("language"), "zh-TW");
        int helpLines = (int) number(root.get("help-lines-per-page"), 8, 3, 100, "help-lines-per-page");

        int topPerPage = (int) number(root.get("top-lines-per-page"), 10, 3, 100, "top-lines-per-page");
        boolean compat = Boolean.TRUE.equals(root.get("compat-commands"));

        Map<String, Currency> currencies = new LinkedHashMap<>();
        String primary = null;
        Map<?, ?> section = map(root.get("currencies"), "currencies");
        for (Map.Entry<?, ?> entry : section.entrySet()) {
            String code = String.valueOf(entry.getKey());
            if (!CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("currency code must be [a-z0-9]{2,16}: " + code);
            }
            Map<?, ?> block = map(entry.getValue(), "currencies." + code);
            if (Boolean.TRUE.equals(block.get("primary"))) {
                if (primary != null) {
                    throw new IllegalArgumentException("more than one primary currency");
                }
                primary = code;
            }
            Map<String, String> names = new LinkedHashMap<>();
            if (block.get("names") != null) {
                map(block.get("names"), "currencies." + code + ".names").forEach((k, v) -> names.put(String.valueOf(k), String.valueOf(v)));
            }
            currencies.put(code, new Currency(code, text(block.get("symbol"), ""), Map.copyOf(names),
                    number(block.get("initial"), 0, 0, Long.MAX_VALUE, code + ".initial"),
                    number(block.get("fee-min"), -1, 0, Long.MAX_VALUE, code + ".fee-min")));
        }
        if (currencies.isEmpty()) {
            throw new IllegalArgumentException("no currency");
        }
        if (primary == null) {
            throw new IllegalArgumentException("no primary currency");
        }

        Map<?, ?> feeBlock = root.get("fee") == null ? Map.of() : map(root.get("fee"), "fee");
        BigDecimal rate = decimal(feeBlock.get("rate"), BigDecimal.ONE, "fee.rate");
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("fee.rate must be 0 to 100");
        }
        long feeMin = number(feeBlock.get("min"), 10, 0, Long.MAX_VALUE, "fee.min");
        List<FeeRule> rules = new ArrayList<>();
        if (root.get("fee-rules") != null) {
            if (!(root.get("fee-rules") instanceof List<?> list)) {
                throw new IllegalArgumentException("fee-rules must be a list");
            }
            for (Object item : list) {
                rules.add(rule(map(item, "fee-rules item")));
            }
        }
        Currency primaryCurrency = currencies.get(primary);
        Fee fee = new Fee(rate, feeMin, List.copyOf(rules));

        Map<String, Map<String, BigDecimal>> rates = new LinkedHashMap<>();
        if (root.get("exchange") != null) {
            Object ratesBlock = map(root.get("exchange"), "exchange").get("rates");
            if (ratesBlock != null) {
                for (Map.Entry<?, ?> from : map(ratesBlock, "exchange.rates").entrySet()) {
                    String source = String.valueOf(from.getKey());
                    Map<String, BigDecimal> targets = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> to : map(from.getValue(), "exchange.rates." + source).entrySet()) {
                        String target = String.valueOf(to.getKey());
                        if (!currencies.containsKey(source) || !currencies.containsKey(target) || source.equals(target)) {
                            throw new IllegalArgumentException("bad exchange pair " + source + " -> " + target);
                        }
                        BigDecimal value = decimal(to.getValue(), null, "exchange.rates." + source + "." + target);
                        if (value.signum() <= 0) {
                            throw new IllegalArgumentException("exchange rate must be above 0: " + source + " -> " + target);
                        }
                        targets.put(target, value);
                    }
                    rates.put(source, Map.copyOf(targets));
                }
            }
        }

        Map<?, ?> ledger = root.get("ledger") == null ? Map.of() : map(root.get("ledger"), "ledger");
        int keepDays = (int) number(ledger.get("keep-days"), 365, 0, 36500, "ledger.keep-days");
        int maxRows = (int) number(ledger.get("max-rows"), 200000, 0, Integer.MAX_VALUE, "ledger.max-rows");
        Objects.requireNonNull(primaryCurrency);
        return new Settings(language, helpLines, topPerPage, compat, Collections.unmodifiableMap(currencies), primary, fee, Map.copyOf(rates), keepDays, maxRows);
    }

    public long feeMin(String code) {
        Currency currency = currencies.get(code);
        return currency == null || currency.feeMin() < 0 ? fee.min() : currency.feeMin();
    }

    private static FeeRule rule(Map<?, ?> m) {
        for (Object key : m.keySet()) {
            if (!Set.of("permission", "min-amount", "max-amount", "time", "days", "dates", "count", "free", "discount", "stack", "id").contains(String.valueOf(key))) {
                throw new IllegalArgumentException("unknown fee-rules key: " + key);
            }
        }
        String permission = m.get("permission") == null ? null : String.valueOf(m.get("permission")).strip();
        long min = number(m.get("min-amount"), 0, 0, Long.MAX_VALUE, "min-amount");
        long max = number(m.get("max-amount"), FeeRule.NO_MAX, 0, Long.MAX_VALUE, "max-amount");
        LocalTime from = null;
        LocalTime to = null;
        if (m.get("time") != null) {
            var matcher = TIME.matcher(String.valueOf(m.get("time")).strip());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("time must look like \"HH:mm-HH:mm\"");
            }
            try {
                from = LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
                to = LocalTime.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)));
            } catch (java.time.DateTimeException e) {
                throw new IllegalArgumentException("bad time " + m.get("time"));
            }
        }
        Set<DayOfWeek> days = new HashSet<>();
        if (m.get("days") != null) {
            if (!(m.get("days") instanceof List<?> list)) {
                throw new IllegalArgumentException("days must be a list");
            }
            for (Object day : list) {
                days.add(day(String.valueOf(day)));
            }
        }
        LocalDate dateFrom = null;
        LocalDate dateTo = null;
        if (m.get("dates") != null) {
            Map<?, ?> dates = map(m.get("dates"), "dates");
            dateFrom = date(dates.get("from"));
            dateTo = date(dates.get("to"));
            if (dateFrom != null && dateTo != null && dateTo.isBefore(dateFrom)) {
                throw new IllegalArgumentException("dates: to is before from");
            }
        }
        int limit = 0;
        Period period = null;
        LocalDate countFrom = null;
        LocalDate countTo = null;
        if (m.get("count") != null) {
            Map<?, ?> count = map(m.get("count"), "count");
            limit = (int) number(count.get("limit"), 0, 1, 1_000_000, "count.limit");
            if (count.get("period") != null) {
                period = Period.parse(String.valueOf(count.get("period"))).orElseThrow(() -> new IllegalArgumentException("count.period must be day, week, month or quarter"));
            } else {
                countFrom = date(count.get("from"));
                countTo = date(count.get("to"));
                if (countFrom == null && countTo == null) {
                    throw new IllegalArgumentException("count needs period or from/to");
                }
                if (countFrom != null && countTo != null && countTo.isBefore(countFrom)) {
                    throw new IllegalArgumentException("count: to is before from");
                }
            }
        }
        boolean free = Boolean.TRUE.equals(m.get("free"));
        int discount = (int) number(m.get("discount"), 0, 0, 100, "discount");
        if (free) {
            discount = 100;
        }
        if (discount <= 0) {
            throw new IllegalArgumentException("a rule needs free: true or discount: 1-100");
        }
        return new FeeRule(permission == null || permission.isEmpty() ? null : permission, min, max, from, to,
                Set.copyOf(days), dateFrom, dateTo, limit, period, countFrom, countTo, discount, Boolean.TRUE.equals(m.get("stack")));
    }

    private static DayOfWeek day(String text) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().toLowerCase(Locale.ROOT).startsWith(t) && t.length() >= 3) {
                return day;
            }
        }
        throw new IllegalArgumentException("unknown day " + text);
    }

    private static LocalDate date(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof Date d) {
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        try {
            return LocalDate.parse(String.valueOf(value).strip());
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("date must look like \"2026-12-31\": " + value);
        }
    }

    private static Map<?, ?> map(Object value, String name) {
        if (value instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException(name + " must be a section");
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static BigDecimal decimal(Object value, BigDecimal fallback, String name) {
        if (value == null) {
            if (fallback == null) {
                throw new IllegalArgumentException(name + " is missing");
            }
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static long number(Object value, long fallback, long min, long max, String name) {
        if (value == null) {
            return fallback;
        }
        try {
            long n = new BigDecimal(String.valueOf(value).strip()).longValueExact();
            if (n < min || n > max) {
                throw new IllegalArgumentException(name + " must be " + min + " to " + max);
            }
            return n;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
    }
}
