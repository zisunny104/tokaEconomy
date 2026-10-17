package dev.toka.economy;

import cn.nukkit.Player;
import dev.toka.economy.core.EconomyService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The entry for other plugins. Call it from the main thread; nothing here waits for the disk. Every change returns a
 * {@link Result} with a {@link Status}; while the economy is not running they all answer {@link Status#UNAVAILABLE}.
 * Currency codes are the lowercase codes of config.yml. Amounts are whole numbers.
 * Changes need an existing account (a player gets one when joining; {@link #createAccount} makes one).
 */
public final class Economy {
    private static volatile EconomyService service;
    private static volatile Players players;

    private Economy() {
    }

    static void install(EconomyService s, Players p) {
        service = s;
        players = p;
    }

    static void uninstall() {
        service = null;
        players = null;
    }

    /** @return true while the economy is running */
    public static boolean available() {
        return service != null;
    }

    // ---- accounts and names ---------------------------------------------------------------------------------------

    /** Makes the account with its starting balances, or just remembers the new name when it exists. */
    public static Result createAccount(UUID id, String name) {
        EconomyService s = service;
        return s == null ? Result.fail(Status.UNAVAILABLE) : s.createAccount(id, name);
    }

    public static boolean hasAccount(UUID id) {
        EconomyService s = service;
        return s != null && s.hasAccount(id);
    }

    /**
     * Name to account for an online player, a player tokaProfiles knows, or a player who has an account. Never reads
     * the disk. Use {@link #resolveAsync} to include players the server only knows from its player files.
     */
    public static Optional<UUID> resolve(String name) {
        Players p = players;
        return p == null ? Optional.empty() : p.resolveFast(name);
    }

    /** Like {@link #resolve} but also looks in the server's player files (off the main thread); calls back on the main thread. */
    public static void resolveAsync(String name, Consumer<Optional<UUID>> done) {
        Players p = players;
        if (p == null) {
            done.accept(Optional.empty());
        } else {
            p.resolve(name, done);
        }
    }

    /** A readable name for the account, or the start of the id when nobody knows it. */
    public static String nameOf(UUID id) {
        Players p = players;
        return p == null ? id.toString().substring(0, 8) : p.display(id);
    }

    // ---- reading --------------------------------------------------------------------------------------------------

    public static Result balance(UUID id, String currency) {
        EconomyService s = service;
        return s == null ? Result.fail(Status.UNAVAILABLE) : s.balance(id, currency);
    }

    public static Result balance(Player player, String currency) {
        return balance(player.getUniqueId(), currency);
    }

    /** OK when the balance is at least the amount, INSUFFICIENT_FUNDS when not. */
    public static Result has(UUID id, String currency, long amount) {
        EconomyService s = service;
        return s == null ? Result.fail(Status.UNAVAILABLE) : s.has(id, currency, amount);
    }

    public static Result has(Player player, String currency, long amount) {
        return has(player.getUniqueId(), currency, amount);
    }

    // ---- changing -------------------------------------------------------------------------------------------------

    public static Result deposit(UUID id, String currency, long amount, Cause cause) {
        EconomyService s = service;
        return s == null ? Result.fail(Status.UNAVAILABLE) : s.deposit(id, currency, amount, cause);
    }

    public static Result deposit(Player player, String currency, long amount, Cause cause) {
        return deposit(player.getUniqueId(), currency, amount, cause);
    }

    public static Result withdraw(UUID id, String currency, long amount, Cause cause) {
        EconomyService s = service;
        return s == null ? Result.fail(Status.UNAVAILABLE) : s.withdraw(id, currency, amount, cause);
    }

    public static Result withdraw(Player player, String currency, long amount, Cause cause) {
        return withdraw(player.getUniqueId(), currency, amount, cause);
    }

    /** Sets the balance (0 or more). */
    public static Result set(UUID id, String currency, long amount, Cause cause) {
        EconomyService s = service;
        return s == null ? Result.fail(Status.UNAVAILABLE) : s.set(id, currency, amount, cause);
    }

    /** Player to player transfer: the sender pays the amount plus the fee, the receiver gets the amount. */
    public static Result transfer(UUID from, UUID to, String currency, long amount, Cause cause) {
        EconomyService s = service;
        return s == null ? Result.fail(Status.UNAVAILABLE) : s.transfer(from, to, currency, amount, cause);
    }

    public static Result transfer(Player from, Player to, String currency, long amount, Cause cause) {
        return transfer(from.getUniqueId(), to.getUniqueId(), currency, amount, cause);
    }

    /** What a transfer would cost, without doing it: the status and {@link Result#fee()}. */
    public static Result previewTransfer(UUID from, UUID to, String currency, long amount) {
        EconomyService s = service;
        return s == null ? Result.fail(Status.UNAVAILABLE) : s.previewTransfer(from, to, currency, amount);
    }

    /** Converts between two currencies by the configured rate, rounded down. */
    public static Result exchange(UUID id, String from, String to, long amount, Cause cause) {
        EconomyService s = service;
        return s == null ? Result.fail(Status.UNAVAILABLE) : s.exchange(id, from, to, amount, cause);
    }

    public static Result exchange(Player player, String from, String to, long amount, Cause cause) {
        return exchange(player.getUniqueId(), from, to, amount, cause);
    }

    /** What an exchange would give ({@link Result#received()}), without doing it. */
    public static Result previewExchange(UUID id, String from, String to, long amount) {
        EconomyService s = service;
        return s == null ? Result.fail(Status.UNAVAILABLE) : s.previewExchange(id, from, to, amount);
    }

    // ---- legacy names ---------------------------------------------------------------------------------------------

    /**
     * Stores the balances of a name from the old (name based) system until that player joins; the balances then move to the
     * player's account (ledger reason {@code legacy-claim}). A name that is already stored (any case) is replaced, so an
     * import can run again. Amounts of 0 are left out. Fails with INVALID_NAME, UNKNOWN_CURRENCY or INVALID_AMOUNT (negative,
     * or nothing above 0). No ledger row is written until the name is claimed.
     */
    public static Result importLegacy(String name, Map<String, Long> balances, Cause cause) {
        EconomyService s = service;
        return s == null ? Result.fail(Status.UNAVAILABLE) : s.importLegacy(name, balances, cause);
    }

    /** How many legacy names still wait to be claimed (0 while the economy is not running). */
    public static int legacyCount() {
        EconomyService s = service;
        return s == null ? 0 : s.legacyCount();
    }

    // ---- currencies -----------------------------------------------------------------------------------------------

    /** The currency codes, in the order of config.yml. */
    public static List<String> currencies() {
        EconomyService s = service;
        return s == null ? List.of() : s.currencies();
    }

    /** The code of the main currency, or null while the economy is not running. */
    public static String primary() {
        EconomyService s = service;
        return s == null ? null : s.primary();
    }

    /** The amount with thousands separators, and with the symbol in front when asked. */
    public static String format(long amount, String currency, boolean withSymbol) {
        EconomyService s = service;
        return s == null ? dev.toka.i18n.NumberText.withCommas(amount) : s.format(amount, currency, withSymbol);
    }

    /** The currency name in the language; falls back to the default language, then the code. */
    public static String currencyName(String currency, String language) {
        EconomyService s = service;
        return s == null ? currency : s.currencyName(currency, language);
    }
}
