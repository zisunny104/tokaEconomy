package dev.toka.economy.core;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Documents of the store. Accounts are "a&lt;uuid&gt;": uuid, name, balances (code to amount). Ledger rows are "l&lt;12 digit id&gt;".
 * The legacy collection is the keys "legacy.n.&lt;lowercase name&gt;" (letters, digits, dot, dash, underscore) or
 * "legacy.h.&lt;hex of the lowercase name&gt;" (any other name): name, balances, source, time.
 */
public final class Codec {
    private Codec() {
    }

    /** One account: uuid, last known name, balance per currency code. */
    public static final class Account {
        public final UUID uuid;
        public String name;
        public final Map<String, Long> balances = new HashMap<>();

        public Account(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public long balance(String code) {
            return balances.getOrDefault(code, 0L);
        }
    }

    /** One row of the ledger; from or to is null for the system. */
    public record LedgerEntry(long id, long time, String currency, UUID from, UUID to, long amount, String reason, String source) {
    }

    public static String accountKey(UUID uuid) {
        return "a" + uuid;
    }

    public static String ledgerKey(long id) {
        return String.format("l%012d", id);
    }

    public static boolean isAccountKey(String key) {
        return key.startsWith("a");
    }

    public static boolean isLedgerKey(String key) {
        return key.startsWith("l") && !isLegacyKey(key);
    }

    /** A name waiting to be claimed (an account of the old system, which had names and no UUIDs). */
    public record Legacy(String name, Map<String, Long> balances, String source, long time) {
        public Legacy {
            balances = Map.copyOf(balances);
        }
    }

    public static final String LEGACY_PREFIX = "legacy.";
    private static final java.util.regex.Pattern PLAIN_NAME = java.util.regex.Pattern.compile("[a-z0-9._-]{1,100}");

    public static boolean isLegacyKey(String key) {
        return key.startsWith(LEGACY_PREFIX);
    }

    /** @param lowerName the name in lowercase */
    public static String legacyKey(String lowerName) {
        if (PLAIN_NAME.matcher(lowerName).matches()) {
            return LEGACY_PREFIX + "n." + lowerName;
        }
        return LEGACY_PREFIX + "h." + java.util.HexFormat.of().formatHex(lowerName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static JsonObject encode(Legacy legacy) {
        JsonObject doc = new JsonObject();
        doc.addProperty("name", legacy.name());
        JsonObject balances = new JsonObject();
        legacy.balances().forEach(balances::addProperty);
        doc.add("balances", balances);
        doc.addProperty("source", legacy.source());
        doc.addProperty("time", legacy.time());
        return doc;
    }

    public static Legacy decodeLegacy(JsonObject doc) {
        Map<String, Long> balances = new java.util.TreeMap<>();
        doc.getAsJsonObject("balances").entrySet().forEach(e -> balances.put(e.getKey(), e.getValue().getAsLong()));
        return new Legacy(doc.get("name").getAsString(), balances, doc.get("source").getAsString(), doc.get("time").getAsLong());
    }

    public static JsonObject encode(Account account) {
        JsonObject doc = new JsonObject();
        doc.addProperty("uuid", account.uuid.toString());
        doc.addProperty("name", account.name == null ? "" : account.name);
        JsonObject balances = new JsonObject();
        account.balances.forEach(balances::addProperty);
        doc.add("balances", balances);
        return doc;
    }

    public static Account decodeAccount(JsonObject doc) {
        Account account = new Account(UUID.fromString(doc.get("uuid").getAsString()), doc.get("name").getAsString());
        doc.getAsJsonObject("balances").entrySet().forEach(e -> account.balances.put(e.getKey(), e.getValue().getAsLong()));
        return account;
    }

    public static JsonObject encode(LedgerEntry entry) {
        JsonObject doc = new JsonObject();
        doc.addProperty("id", entry.id());
        doc.addProperty("time", entry.time());
        doc.addProperty("currency", entry.currency());
        doc.addProperty("from", entry.from() == null ? "" : entry.from().toString());
        doc.addProperty("to", entry.to() == null ? "" : entry.to().toString());
        doc.addProperty("amount", entry.amount());
        doc.addProperty("reason", entry.reason());
        doc.addProperty("source", entry.source());
        return doc;
    }

    public static LedgerEntry decodeLedger(JsonObject doc) {
        return new LedgerEntry(doc.get("id").getAsLong(), doc.get("time").getAsLong(), doc.get("currency").getAsString(),
                uuid(doc.get("from").getAsString()), uuid(doc.get("to").getAsString()), doc.get("amount").getAsLong(),
                doc.get("reason").getAsString().intern(), doc.get("source").getAsString().intern());
    }

    private static UUID uuid(String text) {
        return text.isEmpty() ? null : UUID.fromString(text);
    }
}
