package dev.toka.economy;

import com.google.gson.JsonObject;
import dev.toka.economy.core.Codec;
import dev.toka.economy.core.EconomyService;
import dev.toka.economy.core.Persist;
import org.junit.Test;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.Assert.*;

public class DurableTransferTest {
    private static final Cause PAY = Cause.of("land-purchase", "tokaLand");

    private static class Store implements Persist {
        final Map<String, JsonObject> docs = new HashMap<>();
        boolean failNext;
        public void save(Map<String, JsonObject> values, List<String> deletes) { values.forEach((k, v) -> docs.put(k, v.deepCopy())); deletes.forEach(docs::remove); }
        public CompletableFuture<Void> saveConfirmed(Map<String, JsonObject> values) {
            if (failNext) { failNext = false; return CompletableFuture.failedFuture(new java.io.IOException("disk")); }
            save(values, List.of());
            return CompletableFuture.completedFuture(null);
        }
    }

    private static EconomyService service(Store store) throws Exception {
        return new EconomyService(TestSupport.settings(), store, Clock.systemUTC(), (id, node) -> false, store.docs);
    }

    private static EconomyService withAccounts(Store store, UUID a, UUID b, long balance) throws Exception {
        var economy = service(store);
        economy.createAccount(a, "A");
        economy.createAccount(b, "B");
        economy.deposit(a, "money", balance, Cause.API);
        return economy;
    }

    @Test public void movesTheFullAmountWithoutAFeeAndSurvivesARestart() throws Exception {
        Store store = new Store();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var economy = withAccounts(store, a, b, 100);
        long before = economy.balance(b, "money").balance();
        assertTrue(economy.transferOnce(a, b, "money", 60, PAY, "op-1").join().ok());
        assertEquals(40, economy.balance(a, "money").balance());
        assertEquals(before + 60, economy.balance(b, "money").balance());
        economy = service(store);
        assertTrue(economy.transferOnce(a, b, "money", 60, PAY, "op-1").join().ok());
        assertEquals(40, economy.balance(a, "money").balance());
        assertEquals(before + 60, economy.balance(b, "money").balance());
    }

    @Test public void aFailedWriteLeavesNothingHalfDoneAndRetryCompletesOnce() throws Exception {
        Store store = new Store();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var economy = withAccounts(store, a, b, 100);
        long before = economy.balance(b, "money").balance();
        store.failNext = true;
        assertTrue(economy.transferOnce(a, b, "money", 30, PAY, "op-2").isCompletedExceptionally());
        assertTrue(economy.transferOnce(a, b, "money", 30, PAY, "op-2").join().ok());
        economy = service(store);
        assertEquals(70, economy.balance(a, "money").balance());
        assertEquals(before + 30, economy.balance(b, "money").balance());
        assertTrue(economy.transferOnce(a, b, "money", 30, PAY, "op-2").join().ok());
        assertEquals(70, economy.balance(a, "money").balance());
    }

    @Test public void bothAccountsAreWrittenInTheSameBatch() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        List<Set<String>> batches = new ArrayList<>();
        Store store = new Store() {
            @Override public CompletableFuture<Void> saveConfirmed(Map<String, JsonObject> values) { batches.add(new HashSet<>(values.keySet())); return super.saveConfirmed(values); }
        };
        var economy = withAccounts(store, a, b, 100);
        batches.clear();
        economy.transferOnce(a, b, "money", 10, PAY, "op-3").join();
        assertEquals(1, batches.size());
        assertTrue(batches.get(0).contains(Codec.accountKey(a)));
        assertTrue(batches.get(0).contains(Codec.accountKey(b)));
    }

    @Test public void notEnoughMoneyAndMissingAccountsChangeNothing() throws Exception {
        Store store = new Store();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var economy = withAccounts(store, a, b, 10);
        assertEquals(Status.INSUFFICIENT_FUNDS, economy.transferOnce(a, b, "money", 11, PAY, "op-4").join().status());
        assertEquals(Status.NO_ACCOUNT, economy.transferOnce(a, UUID.randomUUID(), "money", 1, PAY, "op-5").join().status());
        assertEquals(10, economy.balance(a, "money").balance());
    }

    @Test public void anOperationIdCannotBeReusedForADifferentPayment() throws Exception {
        Store store = new Store();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        var economy = withAccounts(store, a, b, 100);
        economy.createAccount(c, "C");
        economy.transferOnce(a, b, "money", 5, PAY, "op-6").join();
        assertTrue(economy.transferOnce(a, b, "money", 6, PAY, "op-6").isCompletedExceptionally());
        assertTrue(economy.transferOnce(a, c, "money", 5, PAY, "op-6").isCompletedExceptionally());
        assertTrue(economy.withdrawOnce(a, "money", 5, PAY, "op-6").isCompletedExceptionally() || economy.balance(a, "money").balance() == 95);
    }

    @Test public void sameAccountIsRejected() throws Exception {
        Store store = new Store();
        UUID a = UUID.randomUUID();
        var economy = service(store);
        economy.createAccount(a, "A");
        assertTrue(economy.transferOnce(a, a, "money", 1, PAY, "op-7").isCompletedExceptionally());
    }

    @Test public void onlyThePayingAccountKeepsAReceipt() throws Exception {
        Store store = new Store();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var economy = withAccounts(store, a, b, 100);
        economy.transferOnce(a, b, "money", 10, PAY, "op-8").join();
        assertEquals(1, store.docs.get(Codec.accountKey(a)).getAsJsonObject("receipts").size());
        assertEquals(0, store.docs.get(Codec.accountKey(b)).getAsJsonObject("receipts").size());
    }
}
