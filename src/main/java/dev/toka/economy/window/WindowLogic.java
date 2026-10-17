package dev.toka.economy.window;

import dev.toka.economy.Result;
import dev.toka.economy.command.CommandLogic;
import dev.toka.economy.command.Line;
import dev.toka.economy.core.EconomyService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the windows decide, with no server types: which currencies and exchange pairs are offered, and what a transfer or
 * an exchange would do before the player confirms. Failures come back as {@link Line}s from {@link CommandLogic#error}.
 */
public final class WindowLogic {
    /** An exchange that has a rate. */
    public record Pair(String from, String to, BigDecimal rate) {
    }

    /**
     * What a transfer or an exchange would do. When {@code error} is set nothing else is meaningful.
     *
     * @param amount   the amount typed
     * @param fee      transfer: the fee on top; exchange: 0
     * @param total    transfer: amount plus fee; exchange: the amount taken
     * @param balance  the balance of the paying currency after
     * @param received exchange: what the player gets
     */
    public record Review(Line error, long amount, long fee, long total, long balance, long received) {
        public boolean ok() {
            return error == null;
        }

        static Review fail(Line error) {
            return new Review(error, 0, 0, 0, 0, 0);
        }
    }

    private final EconomyService service;
    private final CommandLogic logic;

    public WindowLogic(EconomyService service, CommandLogic logic) {
        this.service = service;
        this.logic = logic;
    }

    /** The currency codes with the main one first. */
    public List<String> currencies() {
        List<String> codes = new ArrayList<>(service.currencies());
        codes.remove(service.primary());
        codes.add(0, service.primary());
        return codes;
    }

    /** The exchanges that have a rate between two known currencies, ordered by the currencies of the config. */
    public List<Pair> pairs() {
        List<Pair> pairs = new ArrayList<>();
        List<String> codes = currencies();
        for (String from : codes) {
            Map<String, BigDecimal> targets = service.settings().rates().getOrDefault(from, Map.of());
            for (String to : codes) {
                if (!from.equals(to) && targets.containsKey(to)) {
                    pairs.add(new Pair(from, to, targets.get(to)));
                }
            }
        }
        return pairs;
    }

    public boolean exchangeEnabled() {
        return !pairs().isEmpty();
    }

    /** What paying {@code amountText} of the currency to the player would do; the sender pays the amount plus the fee. */
    public Review transfer(UUID from, UUID to, String targetName, String code, String amountText) {
        long amount = CommandLogic.parseAmount(amountText == null ? "" : amountText.strip());
        if (amount <= 0) {
            return Review.fail(Line.of("err.amount"));
        }
        Result r = service.previewTransfer(from, to, code, amount);
        if (!r.ok()) {
            return Review.fail(logic.error(r, code, targetName));
        }
        return new Review(null, amount, r.fee(), amount + r.fee(), r.balance(), amount);
    }

    /** What exchanging {@code amountText} of the source currency of the pair would give. */
    public Review exchange(UUID id, Pair pair, String amountText) {
        long amount = CommandLogic.parseAmount(amountText == null ? "" : amountText.strip());
        if (amount <= 0) {
            return Review.fail(Line.of("err.amount"));
        }
        Result r = service.previewExchange(id, pair.from(), pair.to(), amount);
        if (!r.ok()) {
            return Review.fail(logic.error(r, pair.from(), pair.from() + " -> " + pair.to()));
        }
        return new Review(null, amount, 0, amount, r.balance(), r.received());
    }
}
