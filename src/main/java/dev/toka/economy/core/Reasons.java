package dev.toka.economy.core;

import java.util.List;

/** Reason codes the plugin itself writes to the ledger. Other plugins choose their own through Cause. */
public final class Reasons {
    public static final String TRANSFER = "transfer";
    public static final String FEE = "fee";
    public static final String EXCHANGE_OUT = "exchange-out";
    public static final String EXCHANGE_IN = "exchange-in";
    public static final String INITIAL = "initial";
    public static final String GIVE = "give";
    public static final String TAKE = "take";
    public static final String SET = "set";
    public static final String API = "api";

    /** Every code above; the language file has status.reason.&lt;code&gt; for each. */
    public static final List<String> ALL = List.of(TRANSFER, FEE, EXCHANGE_OUT, EXCHANGE_IN, INITIAL, GIVE, TAKE, SET, API);

    private Reasons() {
    }
}
