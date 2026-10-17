package dev.toka.economy.core;

import com.google.gson.JsonObject;
import dev.toka.economy.Cause;
import dev.toka.economy.Result;
import dev.toka.economy.Status;
import dev.toka.economy.core.Codec.Account;
import dev.toka.economy.core.Codec.LedgerEntry;
import dev.toka.i18n.NumberText;
import dev.toka.i18n.Pager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * The economy in memory. Every call changes memory at once and hands the changed documents to {@link Persist} as one
 * batch, so it never waits for the disk. A transfer checks everything first and then changes everything: it either
 * happens completely or not at all. Calls are safe from any thread.
 */
public final class EconomyService {
    public static final String FREE_PERMISSION = "tokaEconomy.fee.free";

    /** A page of ledger rows, newest first. */
    public record LedgerPage(List<LedgerEntry> entries, int page, int pages, int total) {
    }

    private final Object lock = new Object();
    private final Persist persist;
    private final Clock clock;
    private final PermissionLookup permissions;
    private volatile Settings settings;
    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<String, UUID> names = new HashMap<>();
    private final ArrayDeque<LedgerEntry> ledger = new ArrayDeque<>();
    private long nextId = 1;

    /** @param snapshot every document of the store (empty for a new one) */
    public EconomyService(Settings settings, Persist persist, Clock clock, PermissionLookup permissions, Map<String, JsonObject> snapshot) {
        this.settings = settings;
        this.persist = persist;
        this.clock = clock;
        this.permissions = permissions;
        List<LedgerEntry> rows = new ArrayList<>();
        for (Map.Entry<String, JsonObject> doc : snapshot.entrySet()) {
            if (Codec.isAccountKey(doc.getKey())) {
                Account account = Codec.decodeAccount(doc.getValue());
                accounts.put(account.uuid, account);
                indexName(account);
            } else if (Codec.isLedgerKey(doc.getKey())) {
                rows.add(Codec.decodeLedger(doc.getValue()));
            }
        }
        rows.sort(Comparator.comparingLong(LedgerEntry::id));
        for (LedgerEntry row : rows) {
            ledger.addLast(row);
            nextId = Math.max(nextId, row.id() + 1);
        }
    }

    public Settings settings() {
        return settings;
    }

    /** Takes new settings (from /economy reload). Balances of currencies that left the file stay stored but are not reachable. */
    public void settings(Settings next) {
        this.settings = next;
    }

    // ---- accounts -------------------------------------------------------------------------------------------------

    /** Creates the account with the starting balances, or only updates the remembered name when it exists. */
    public Result createAccount(UUID id, String name) {
        Settings s = settings;
        synchronized (lock) {
            Account account = accounts.get(id);
            Map<String, JsonObject> batch = new LinkedHashMap<>();
            if (account == null) {
                account = new Account(id, name == null ? "" : name);
                accounts.put(id, account);
                indexName(account);
                for (Currency c : s.currencies().values()) {
                    if (c.initial() > 0) {
                        account.balances.put(c.code(), c.initial());
                        log(batch, c.code(), null, id, c.initial(), new Cause(Reasons.INITIAL, "tokaEconomy"));
                    }
                }
            } else if (name != null && !name.isEmpty() && !name.equals(account.name)) {
                unindexName(account);
                account.name = name;
                indexName(account);
            } else {
                return Result.fail(Status.OK);
            }
            batch.put(Codec.accountKey(id), Codec.encode(account));
            persist.save(batch);
            return Result.fail(Status.OK);
        }
    }

    public boolean hasAccount(UUID id) {
        synchronized (lock) {
            return accounts.containsKey(id);
        }
    }

    public Optional<UUID> findByName(String name) {
        synchronized (lock) {
            return Optional.ofNullable(names.get(name.toLowerCase(Locale.ROOT)));
        }
    }

    /** @return the last known name of the account, or empty */
    public Optional<String> nameOf(UUID id) {
        synchronized (lock) {
            Account account = accounts.get(id);
            return account == null || account.name.isEmpty() ? Optional.empty() : Optional.of(account.name);
        }
    }

    // ---- reading --------------------------------------------------------------------------------------------------

    public Result balance(UUID id, String code) {
        Currency c = currency(code);
        if (c == null) {
            return Result.fail(Status.UNKNOWN_CURRENCY);
        }
        synchronized (lock) {
            Account account = accounts.get(id);
            return account == null ? Result.fail(Status.NO_ACCOUNT) : new Result(Status.OK, 0, 0, account.balance(c.code()), 0);
        }
    }

    /** OK when the balance is at least the amount (0 or more), INSUFFICIENT_FUNDS otherwise. */
    public Result has(UUID id, String code, long amount) {
        if (amount < 0) {
            return Result.fail(Status.INVALID_AMOUNT);
        }
        Result r = balance(id, code);
        if (!r.ok()) {
            return r;
        }
        return new Result(r.balance() >= amount ? Status.OK : Status.INSUFFICIENT_FUNDS, amount, 0, r.balance(), 0);
    }

    // ---- changing -------------------------------------------------------------------------------------------------

    public Result deposit(UUID id, String code, long amount, Cause cause) {
        Currency c = currency(code);
        if (c == null) {
            return Result.fail(Status.UNKNOWN_CURRENCY);
        }
        if (amount <= 0) {
            return Result.fail(Status.INVALID_AMOUNT);
        }
        synchronized (lock) {
            Account account = accounts.get(id);
            if (account == null) {
                return Result.fail(Status.NO_ACCOUNT);
            }
            long now = account.balance(c.code());
            if (now > Long.MAX_VALUE - amount) {
                return new Result(Status.OVERFLOW, amount, 0, now, 0);
            }
            account.balances.put(c.code(), now + amount);
            Map<String, JsonObject> batch = new LinkedHashMap<>();
            log(batch, c.code(), null, id, amount, cause);
            batch.put(Codec.accountKey(id), Codec.encode(account));
            persist.save(batch);
            return new Result(Status.OK, amount, 0, now + amount, 0);
        }
    }

    public Result withdraw(UUID id, String code, long amount, Cause cause) {
        Currency c = currency(code);
        if (c == null) {
            return Result.fail(Status.UNKNOWN_CURRENCY);
        }
        if (amount <= 0) {
            return Result.fail(Status.INVALID_AMOUNT);
        }
        synchronized (lock) {
            Account account = accounts.get(id);
            if (account == null) {
                return Result.fail(Status.NO_ACCOUNT);
            }
            long now = account.balance(c.code());
            if (now < amount) {
                return new Result(Status.INSUFFICIENT_FUNDS, amount, 0, now, 0);
            }
            account.balances.put(c.code(), now - amount);
            Map<String, JsonObject> batch = new LinkedHashMap<>();
            log(batch, c.code(), id, null, amount, cause);
            batch.put(Codec.accountKey(id), Codec.encode(account));
            persist.save(batch);
            return new Result(Status.OK, amount, 0, now - amount, 0);
        }
    }

    /** Sets the balance (0 or more). The ledger gets the difference. */
    public Result set(UUID id, String code, long amount, Cause cause) {
        Currency c = currency(code);
        if (c == null) {
            return Result.fail(Status.UNKNOWN_CURRENCY);
        }
        if (amount < 0) {
            return Result.fail(Status.INVALID_AMOUNT);
        }
        synchronized (lock) {
            Account account = accounts.get(id);
            if (account == null) {
                return Result.fail(Status.NO_ACCOUNT);
            }
            long now = account.balance(c.code());
            account.balances.put(c.code(), amount);
            Map<String, JsonObject> batch = new LinkedHashMap<>();
            if (amount > now) {
                log(batch, c.code(), null, id, amount - now, cause);
            } else if (amount < now) {
                log(batch, c.code(), id, null, now - amount, cause);
            }
            batch.put(Codec.accountKey(id), Codec.encode(account));
            persist.save(batch);
            return new Result(Status.OK, amount, 0, amount, 0);
        }
    }

    /** Player to player transfer. The sender pays the amount plus the fee; the receiver gets the whole amount. */
    public Result transfer(UUID from, UUID to, String code, long amount, Cause cause) {
        return transfer(from, to, code, amount, cause, true);
    }

    /** What {@link #transfer} would do, without doing it. */
    public Result previewTransfer(UUID from, UUID to, String code, long amount) {
        return transfer(from, to, code, amount, Cause.API, false);
    }

    private Result transfer(UUID from, UUID to, String code, long amount, Cause cause, boolean apply) {
        Currency c = currency(code);
        if (c == null) {
            return Result.fail(Status.UNKNOWN_CURRENCY);
        }
        if (amount <= 0) {
            return Result.fail(Status.INVALID_AMOUNT);
        }
        if (from.equals(to)) {
            return Result.fail(Status.SAME_ACCOUNT);
        }
        Settings s = settings;
        synchronized (lock) {
            Account sender = accounts.get(from);
            Account receiver = accounts.get(to);
            if (sender == null || receiver == null) {
                return Result.fail(Status.NO_ACCOUNT);
            }
            long fee = feeFor(s, from, c.code(), amount);
            long senderBalance = sender.balance(c.code());
            long total;
            try {
                total = Math.addExact(amount, fee);
            } catch (ArithmeticException e) {
                return new Result(Status.OVERFLOW, amount, fee, senderBalance, 0);
            }
            if (senderBalance < total) {
                return new Result(Status.INSUFFICIENT_FUNDS, total, fee, senderBalance, 0);
            }
            long receiverBalance = receiver.balance(c.code());
            if (receiverBalance > Long.MAX_VALUE - amount) {
                return new Result(Status.OVERFLOW, amount, fee, senderBalance, 0);
            }
            if (apply) {
                sender.balances.put(c.code(), senderBalance - total);
                receiver.balances.put(c.code(), receiverBalance + amount);
                Map<String, JsonObject> batch = new LinkedHashMap<>();
                log(batch, c.code(), from, to, amount, new Cause(Reasons.TRANSFER, cause.source()));
                if (fee > 0) {
                    log(batch, c.code(), from, null, fee, new Cause(Reasons.FEE, cause.source()));
                }
                batch.put(Codec.accountKey(from), Codec.encode(sender));
                batch.put(Codec.accountKey(to), Codec.encode(receiver));
                persist.save(batch);
            }
            return new Result(Status.OK, amount, fee, senderBalance - total, amount);
        }
    }

    /** The fee a transfer from this account would cost now (0 for an unknown currency). */
    public long fee(UUID from, String code, long amount) {
        Currency c = currency(code);
        if (c == null || amount <= 0) {
            return 0;
        }
        synchronized (lock) {
            return feeFor(settings, from, c.code(), amount);
        }
    }

    private long feeFor(Settings s, UUID from, String code, long amount) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        return FeeCalc.fee(s.fee(), s.feeMin(code), amount, permissions.has(from, FREE_PERMISSION),
                node -> permissions.has(from, node), now, (a, b) -> countTransfers(from, code, a, b));
    }

    /** Transfers the sender made in the time range; the newest rows come first, so it stops at the first old one. */
    private int countTransfers(UUID sender, String code, LocalDateTime fromTime, LocalDateTime toTime) {
        int count = 0;
        Iterator<LedgerEntry> it = ledger.descendingIterator();
        while (it.hasNext()) {
            LedgerEntry e = it.next();
            LocalDateTime when = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.time()), clock.getZone());
            if (when.isBefore(fromTime)) {
                break;
            }
            if (when.isBefore(toTime) && Reasons.TRANSFER.equals(e.reason()) && sender.equals(e.from())) {
                count++;
            }
        }
        return count;
    }

    // ---- exchange -------------------------------------------------------------------------------------------------

    public Result exchange(UUID id, String fromCode, String toCode, long amount, Cause cause) {
        return exchange(id, fromCode, toCode, amount, cause, true);
    }

    /** What {@link #exchange} would give, without doing it: {@code received} is the amount of the target currency. */
    public Result previewExchange(UUID id, String fromCode, String toCode, long amount) {
        return exchange(id, fromCode, toCode, amount, Cause.API, false);
    }

    /** The exchange result for an amount: the target amount, rounded down. */
    public static long convert(BigDecimal rate, long amount) {
        try {
            return BigDecimal.valueOf(amount).multiply(rate).setScale(0, RoundingMode.DOWN).longValueExact();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private Result exchange(UUID id, String fromCode, String toCode, long amount, Cause cause, boolean apply) {
        Currency from = currency(fromCode);
        Currency to = currency(toCode);
        if (from == null || to == null) {
            return Result.fail(Status.UNKNOWN_CURRENCY);
        }
        if (amount <= 0) {
            return Result.fail(Status.INVALID_AMOUNT);
        }
        BigDecimal rate = settings.rates().getOrDefault(from.code(), Map.of()).get(to.code());
        if (rate == null) {
            return Result.fail(Status.NO_RATE);
        }
        long received = convert(rate, amount);
        if (received <= 0) {
            return Result.fail(Status.INVALID_AMOUNT);
        }
        synchronized (lock) {
            Account account = accounts.get(id);
            if (account == null) {
                return Result.fail(Status.NO_ACCOUNT);
            }
            long have = account.balance(from.code());
            if (have < amount) {
                return new Result(Status.INSUFFICIENT_FUNDS, amount, 0, have, received);
            }
            long target = account.balance(to.code());
            if (received == Long.MAX_VALUE || target > Long.MAX_VALUE - received) {
                return new Result(Status.OVERFLOW, amount, 0, have, received);
            }
            if (apply) {
                account.balances.put(from.code(), have - amount);
                account.balances.put(to.code(), target + received);
                Map<String, JsonObject> batch = new LinkedHashMap<>();
                log(batch, from.code(), id, null, amount, new Cause(Reasons.EXCHANGE_OUT, cause.source()));
                log(batch, to.code(), null, id, received, new Cause(Reasons.EXCHANGE_IN, cause.source()));
                batch.put(Codec.accountKey(id), Codec.encode(account));
                persist.save(batch);
            }
            return new Result(Status.OK, amount, 0, have - amount, received);
        }
    }

    // ---- display --------------------------------------------------------------------------------------------------

    public boolean isCurrency(String code) {
        return currency(code) != null;
    }

    public List<String> currencies() {
        return List.copyOf(settings.currencies().keySet());
    }

    public String primary() {
        return settings.primary();
    }

    /** The number with thousands separators; with symbol, the currency symbol in front. An unknown currency gives the number only. */
    public String format(long amount, String code, boolean withSymbol) {
        String number = NumberText.withCommas(amount);
        Currency c = currency(code);
        if (!withSymbol || c == null || c.symbol().isEmpty()) {
            return number;
        }
        return amount < 0 ? "-" + c.symbol() + number.substring(1) : c.symbol() + number;
    }

    /** Name in the language, else in the default language, else the code. */
    public String currencyName(String code, String language) {
        Currency c = currency(code);
        if (c == null) {
            return code;
        }
        String name = language == null ? null : c.names().get(language);
        if (name == null) {
            name = c.names().get(settings.language());
        }
        return name == null ? c.code() : name;
    }

    // ---- ledger ---------------------------------------------------------------------------------------------------

    /** @param who rows where the player is sender or receiver; null for everybody */
    public LedgerPage ledger(UUID who, int page, int perPage) {
        List<LedgerEntry> matching = new ArrayList<>();
        synchronized (lock) {
            Iterator<LedgerEntry> it = ledger.descendingIterator();
            while (it.hasNext()) {
                LedgerEntry e = it.next();
                if (who == null || who.equals(e.from()) || who.equals(e.to())) {
                    matching.add(e);
                }
            }
        }
        Pager.Page<LedgerEntry> p = Pager.of(matching, perPage, page);
        return new LedgerPage(p.lines(), p.page(), p.pages(), matching.size());
    }

    /** One line of the leaderboard. */
    public record Rank(int rank, UUID uuid, String name, long balance) {
    }

    /** A page of the leaderboard. */
    public record TopPage(List<Rank> entries, int page, int pages, int total) {
    }

    /** Accounts with a balance above 0 in the currency, richest first (same balance: by name). Unknown currency gives an empty page. */
    public TopPage top(String code, int page, int perPage) {
        Currency c = currency(code);
        List<Account> list = new ArrayList<>();
        if (c != null) {
            synchronized (lock) {
                for (Account a : accounts.values()) {
                    if (a.balance(c.code()) > 0) {
                        Account copy = new Account(a.uuid, a.name);
                        copy.balances.put(c.code(), a.balance(c.code()));
                        list.add(copy);
                    }
                }
            }
        }
        list.sort(Comparator.<Account>comparingLong(a -> -a.balance(c.code())).thenComparing(a -> a.name.toLowerCase(Locale.ROOT)));
        Pager.Page<Account> p = Pager.of(list, perPage, page);
        List<Rank> ranks = new ArrayList<>();
        int rank = (p.page() - 1) * Math.max(1, perPage);
        for (Account a : p.lines()) {
            ranks.add(new Rank(++rank, a.uuid, a.name, a.balance(c.code())));
        }
        return new TopPage(ranks, p.page(), p.pages(), list.size());
    }

    public int ledgerSize() {
        synchronized (lock) {
            return ledger.size();
        }
    }

    /**
     * Removes up to {@code limit} of the oldest rows that are too old or beyond the row limit, and returns their store
     * keys for deletion. Meant to be called again until it returns fewer than {@code limit} keys.
     */
    public List<String> trim(int limit) {
        Settings s = settings;
        long cutoff = s.keepDays() > 0 ? clock.millis() - s.keepDays() * 86_400_000L : Long.MIN_VALUE;
        List<String> keys = new ArrayList<>();
        synchronized (lock) {
            while (keys.size() < limit && !ledger.isEmpty()) {
                LedgerEntry oldest = ledger.peekFirst();
                boolean tooOld = s.keepDays() > 0 && oldest.time() < cutoff;
                boolean tooMany = s.maxRows() > 0 && ledger.size() > s.maxRows();
                if (!tooOld && !tooMany) {
                    break;
                }
                ledger.pollFirst();
                keys.add(Codec.ledgerKey(oldest.id()));
            }
        }
        return keys;
    }

    // ---- internals ------------------------------------------------------------------------------------------------

    private Currency currency(String code) {
        return code == null ? null : settings.currencies().get(code.toLowerCase(Locale.ROOT));
    }

    private void log(Map<String, JsonObject> batch, String code, UUID from, UUID to, long amount, Cause cause) {
        LedgerEntry entry = new LedgerEntry(nextId++, clock.millis(), code, from, to, amount, cause.reason().intern(), cause.source().intern());
        ledger.addLast(entry);
        batch.put(Codec.ledgerKey(entry.id()), Codec.encode(entry));
    }

    private void indexName(Account account) {
        if (!account.name.isEmpty()) {
            names.put(account.name.toLowerCase(Locale.ROOT), account.uuid);
        }
    }

    private void unindexName(Account account) {
        if (!account.name.isEmpty()) {
            names.remove(account.name.toLowerCase(Locale.ROOT), account.uuid);
        }
    }

    /** For tests and diagnostics. */
    public ZoneId zone() {
        return clock.getZone();
    }
}
