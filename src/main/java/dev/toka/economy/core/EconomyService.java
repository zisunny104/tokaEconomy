package dev.toka.economy.core;

import com.google.gson.JsonObject;
import dev.toka.economy.Cause;
import dev.toka.economy.Result;
import dev.toka.economy.Status;
import dev.toka.economy.core.Codec.Account;
import dev.toka.economy.core.Codec.LedgerEntry;
import dev.toka.economy.core.Codec.Legacy;
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
    private final Map<String, PaymentAttempt> paymentAttempts = new HashMap<>();

    private static final class PaymentAttempt {
        final Map<String, JsonObject> ledger;
        java.util.concurrent.CompletableFuture<Result> future;
        PaymentAttempt(Map<String, JsonObject> ledger) { this.ledger = Map.copyOf(ledger); }
    }
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
    private final Map<String, Legacy> legacy = new TreeMap<>();
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
            } else if (Codec.isLegacyKey(doc.getKey())) {
                Legacy entry = Codec.decodeLegacy(doc.getValue());
                legacy.put(entry.name().toLowerCase(Locale.ROOT), entry);
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

    /**
     * Durable, idempotent deposit for rewards. The same source and operation ID must always
     * describe the same amount/currency. Completion can be on the writer thread.
     */
    public java.util.concurrent.CompletableFuture<Result> depositOnce(UUID id, String code, long amount, Cause cause, String operationId) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(cause);
        if (operationId == null || operationId.isBlank() || operationId.length() > 160 || operationId.chars().anyMatch(Character::isISOControl)) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Invalid operation ID"));
        }
        Currency currency = currency(code);
        if (currency == null) return java.util.concurrent.CompletableFuture.completedFuture(Result.fail(Status.UNKNOWN_CURRENCY));
        if (amount <= 0) return java.util.concurrent.CompletableFuture.completedFuture(Result.fail(Status.INVALID_AMOUNT));
        String key = receiptKey(cause.source(), operationId);
        String pendingKey = id + ":" + key;
        synchronized (lock) {
            Account account = accounts.get(id);
            if (account == null) return java.util.concurrent.CompletableFuture.completedFuture(Result.fail(Status.NO_ACCOUNT));
            JsonObject receipt = account.receipts.get(key);
            PaymentAttempt attempt = paymentAttempts.get(pendingKey);
            if (receipt != null) {
                if (!receipt.get("operation").getAsString().equals(operationId)
                        || !receipt.get("source").getAsString().equals(cause.source())
                        || !receipt.get("currency").getAsString().equals(currency.code())
                        || receipt.get("amount").getAsLong() != amount) {
                    return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Operation ID reused with different payment"));
                }
                if (attempt == null) return java.util.concurrent.CompletableFuture.completedFuture(receiptResult(receipt));
                if (attempt.future != null && !attempt.future.isDone()) return attempt.future.copy();
            } else {
                long balance = account.balance(currency.code());
                if (balance > Long.MAX_VALUE - amount) return java.util.concurrent.CompletableFuture.completedFuture(Result.fail(Status.OVERFLOW));
                receipt = new JsonObject();
                receipt.addProperty("operation", operationId);
                receipt.addProperty("source", cause.source());
                receipt.addProperty("currency", currency.code());
                receipt.addProperty("amount", amount);
                receipt.addProperty("balance", balance + amount);
                receipt.addProperty("time", clock.millis());
                account.balances.put(currency.code(), balance + amount);
                account.receipts.put(key, receipt);
                Map<String, JsonObject> ledger = new LinkedHashMap<>();
                log(ledger, currency.code(), null, id, amount, cause);
                attempt = new PaymentAttempt(ledger);
                paymentAttempts.put(pendingKey, attempt);
            }
            // Every ordinary account write also includes the receipt. A later successful
            // account write can never persist this credit without its deduplication token.
            Map<String, JsonObject> batch = new LinkedHashMap<>(attempt.ledger);
            batch.put(Codec.accountKey(id), Codec.encode(account));
            java.util.concurrent.CompletableFuture<Result> answer = new java.util.concurrent.CompletableFuture<>();
            attempt.future = answer;
            Result result = receiptResult(receipt);
            java.util.concurrent.CompletableFuture<Void> saved;
            try { saved = persist.saveConfirmed(batch); }
            catch (RuntimeException error) { answer.completeExceptionally(error); return answer.copy(); }
            saved.whenComplete((ignored, error) -> {
                if (error != null) {
                    answer.completeExceptionally(error);
                } else {
                    synchronized (lock) { paymentAttempts.remove(pendingKey); }
                    answer.complete(result);
                }
            });
            return answer.copy();
        }
    }

    private static Result receiptResult(JsonObject receipt) {
        return new Result(Status.OK, receipt.get("amount").getAsLong(), 0, receipt.get("balance").getAsLong(), 0);
    }

    private static String receiptKey(String source, String operation) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((source + "\u0000" + operation).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
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

    // ---- legacy accounts ------------------------------------------------------------------------------------------

    /** What {@link #join} did with a legacy account of the player's name. */
    public enum Outcome {
        /** No legacy account of that name. */
        NONE,
        /** Its balances were added to the account and it was removed. */
        CLAIMED,
        /** Kept: the account already has a balance. */
        HELD_BALANCE,
        /** Kept: another account has the same name. */
        HELD_CONFLICT
    }

    /** @param legacy the account that was (or would have been) claimed; null for NONE */
    public record Join(Outcome outcome, Legacy legacy) {
    }

    /** @param legacy the account that was claimed; null when it failed */
    public record Claim(Status status, Legacy legacy) {
    }

    /** A page of legacy accounts, ordered by name. */
    public record LegacyPage(List<Legacy> entries, int page, int pages, int total) {
    }

    /**
     * Stores the balances of a name of the old system, replacing one of the same name (case does not matter). Amounts of 0 are
     * left out; negative ones, unknown currencies and a name that is empty, too long or has control characters fail.
     */
    public Result importLegacy(String name, Map<String, Long> balances, Cause cause) {
        String clean = name == null ? "" : name.strip();
        String lower = clean.toLowerCase(Locale.ROOT);
        if (clean.isEmpty() || clean.length() > 100 || clean.chars().anyMatch(Character::isISOControl) || Codec.legacyKey(lower).length() > 128) {
            return Result.fail(Status.INVALID_NAME);
        }
        Map<String, Long> kept = new TreeMap<>();
        for (Map.Entry<String, Long> e : balances.entrySet()) {
            Currency c = currency(e.getKey());
            if (c == null) {
                return Result.fail(Status.UNKNOWN_CURRENCY);
            }
            if (e.getValue() == null || e.getValue() < 0) {
                return Result.fail(Status.INVALID_AMOUNT);
            }
            if (e.getValue() > 0) {
                try {
                    kept.merge(c.code(), e.getValue(), Math::addExact);
                } catch (ArithmeticException ex) {
                    return Result.fail(Status.OVERFLOW);
                }
            }
        }
        if (kept.isEmpty()) {
            return Result.fail(Status.INVALID_AMOUNT);
        }
        Legacy entry = new Legacy(clean, kept, cause.source(), clock.millis());
        synchronized (lock) {
            legacy.put(lower, entry);
            persist.save(Map.of(Codec.legacyKey(lower), Codec.encode(entry)));
        }
        return Result.fail(Status.OK);
    }

    public int legacyCount() {
        synchronized (lock) {
            return legacy.size();
        }
    }

    public LegacyPage legacies(int page, int perPage) {
        List<Legacy> all;
        synchronized (lock) {
            all = new ArrayList<>(legacy.values());
        }
        Pager.Page<Legacy> p = Pager.of(all, perPage, page);
        return new LegacyPage(p.lines(), p.page(), p.pages(), all.size());
    }

    /** Removes the legacy account without paying it to anyone. @return false when there is none of that name */
    public boolean dropLegacy(String name) {
        String lower = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        synchronized (lock) {
            if (legacy.remove(lower) == null) {
                return false;
            }
            persist.save(Map.of(), List.of(Codec.legacyKey(lower)));
            return true;
        }
    }

    /**
     * A player joined: makes the account, and first takes over the legacy account of the same name (any case) when the player's
     * account is new or has no balance and no other account has that name. The account then has the legacy balances instead
     * of the starting balances. Memory only; the writes go to the background.
     */
    public Join join(UUID id, String name) {
        String lower = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        synchronized (lock) {
            Legacy found = legacy.get(lower);
            if (found == null) {
                createAccount(id, name);
                return new Join(Outcome.NONE, null);
            }
            Account account = accounts.get(id);
            if (account != null && account.balances.values().stream().anyMatch(v -> v != 0)) {
                createAccount(id, name);
                return new Join(Outcome.HELD_BALANCE, found);
            }
            for (Account other : accounts.values()) {
                if (!other.uuid.equals(id) && other.name.equalsIgnoreCase(lower)) {
                    createAccount(id, name);
                    return new Join(Outcome.HELD_CONFLICT, found);
                }
            }
            Status status = pay(found, lower, id, name, new Cause(Reasons.LEGACY_CLAIM, "tokaEconomy"));
            if (status != Status.OK) {
                createAccount(id, name);
            }
            return new Join(status == Status.OK ? Outcome.CLAIMED : Outcome.HELD_BALANCE, found);
        }
    }

    /** Adds the legacy balances to the account (made without starting balances when missing), whatever it holds. */
    public Claim claimLegacy(String name, UUID id, String playerName, Cause cause) {
        String lower = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        synchronized (lock) {
            Legacy found = legacy.get(lower);
            if (found == null) {
                return new Claim(Status.NO_LEGACY, null);
            }
            Status status = pay(found, lower, id, playerName, cause);
            return new Claim(status, status == Status.OK ? found : null);
        }
    }

    /** Must hold the lock. Nothing changes unless it returns OK (OVERFLOW otherwise). */
    private Status pay(Legacy found, String lower, UUID id, String playerName, Cause cause) {
        Account account = accounts.get(id);
        if (account != null) {
            for (Map.Entry<String, Long> e : found.balances().entrySet()) {
                if (account.balance(e.getKey()) > Long.MAX_VALUE - e.getValue()) {
                    return Status.OVERFLOW;
                }
            }
        } else {
            account = new Account(id, playerName == null ? "" : playerName);
            accounts.put(id, account);
        }
        if (playerName != null && !playerName.isEmpty() && !playerName.equals(account.name)) {
            unindexName(account);
            account.name = playerName;
        }
        indexName(account);
        Map<String, JsonObject> batch = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : found.balances().entrySet()) {
            account.balances.put(e.getKey(), account.balance(e.getKey()) + e.getValue());
            log(batch, e.getKey(), null, id, e.getValue(), cause);
        }
        batch.put(Codec.accountKey(id), Codec.encode(account));
        legacy.remove(lower);
        persist.save(batch, List.of(Codec.legacyKey(lower)));
        return Status.OK;
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
