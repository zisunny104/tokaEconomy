package dev.toka.economy;

/** What happened to an economy call. Only {@link #OK} changes (or, for a preview, would change) balances. */
public enum Status {
    OK,
    /** The amount is zero, negative, too small to give anything, or not allowed for this call. */
    INVALID_AMOUNT,
    /** The currency code is not in config.yml. */
    UNKNOWN_CURRENCY,
    /** The account does not exist (for a transfer: the sender or the receiver). */
    NO_ACCOUNT,
    /** The balance cannot pay the amount (plus fee). */
    INSUFFICIENT_FUNDS,
    /** The new balance would be larger than a long. */
    OVERFLOW,
    /** A transfer from an account to itself. */
    SAME_ACCOUNT,
    /** No exchange rate between the two currencies. */
    NO_RATE,
    /** The legacy name is empty, too long or has control characters. */
    INVALID_NAME,
    /** There is no legacy account with that name. */
    NO_LEGACY,
    /** The economy is not running (disabled, failed to start or stopped). */
    UNAVAILABLE
}
