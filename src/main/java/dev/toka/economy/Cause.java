package dev.toka.economy;

import java.util.Objects;

/**
 * Why a change happened, stored in the ledger.
 *
 * @param reason a short code such as "shop-buy" (up to 32 characters)
 * @param source who did it, usually the plugin name (up to 64 characters)
 */
public record Cause(String reason, String source) {
    public static final Cause API = new Cause("api", "unknown");

    public Cause {
        Objects.requireNonNull(reason);
        Objects.requireNonNull(source);
        reason = cut(reason, 32);
        source = cut(source, 64);
    }

    public static Cause of(String reason, String source) {
        return new Cause(reason, source);
    }

    private static String cut(String text, int max) {
        String clean = text.strip().replaceAll("[\\p{Cntrl}]", "");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }
}
