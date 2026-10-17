package dev.toka.economy;

/**
 * Result of an economy call.
 *
 * @param status   what happened
 * @param amount   deposit/withdraw/transfer/exchange: the amount asked; insufficient funds: the amount needed including the fee
 * @param fee      transfer fee paid by the sender on top of the amount (0 when none)
 * @param balance  balance of the account that was asked, or the sender, after the call (for a failure: the balance now)
 * @param received exchange: the amount of the target currency; transfer: the amount the receiver got
 */
public record Result(Status status, long amount, long fee, long balance, long received) {
    public static Result fail(Status status) {
        return new Result(status, 0, 0, 0, 0);
    }

    public boolean ok() {
        return status == Status.OK;
    }
}
