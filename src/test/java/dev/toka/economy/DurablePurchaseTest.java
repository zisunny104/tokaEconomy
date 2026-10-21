package dev.toka.economy;

import com.google.gson.JsonObject;
import dev.toka.economy.core.*;
import org.junit.Test;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class DurablePurchaseTest {
    private static final Cause BUY=Cause.of("land-purchase","tokaLand"),REFUND=Cause.of("land-refund","tokaLand");
    private static final class Store implements Persist{
        Map<String,JsonObject> docs=new HashMap<>();boolean failNext;
        public void save(Map<String,JsonObject> values,List<String> deletes){values.forEach((k,v)->docs.put(k,v.deepCopy()));deletes.forEach(docs::remove);}
        public CompletableFuture<Void> saveConfirmed(Map<String,JsonObject> values){if(failNext){failNext=false;return CompletableFuture.failedFuture(new java.io.IOException("disk"));}save(values,List.of());return CompletableFuture.completedFuture(null);}
    }
    private EconomyService service(Store store)throws Exception{return new EconomyService(TestSupport.settings(),store,Clock.systemUTC(),(id,node)->false,store.docs);}
    @Test public void purchaseAndExactRefundAreIdempotentAcrossRestarts()throws Exception{
        Store store=new Store();UUID player=UUID.randomUUID();var economy=service(store);economy.createAccount(player,"Player");economy.deposit(player,"money",200,Cause.API);
        assertTrue(economy.withdrawOnce(player,"money",75,BUY,"claim-1").join().ok());assertEquals(125,economy.balance(player,"money").balance());
        economy=service(store);assertTrue(economy.withdrawOnce(player,"money",75,BUY,"claim-1").join().ok());assertEquals(125,economy.balance(player,"money").balance());
        assertTrue(economy.refundWithdrawalOnce(player,BUY,"claim-1",REFUND).join().ok());assertEquals(200,economy.balance(player,"money").balance());
        economy=service(store);assertTrue(economy.refundWithdrawalOnce(player,BUY,"claim-1",REFUND).join().ok());assertEquals(200,economy.balance(player,"money").balance());
        assertTrue(economy.depositOnce(player,"money",75,BUY,"claim-1").isCompletedExceptionally());
        assertTrue(economy.withdrawOnce(player,"money",76,BUY,"claim-1").isCompletedExceptionally());
    }
    @Test public void failedWriteRetryDoesNotDebitTwice()throws Exception{
        Store store=new Store();UUID player=UUID.randomUUID();var economy=service(store);economy.createAccount(player,"Player");economy.deposit(player,"money",100,Cause.API);
        store.failNext=true;assertTrue(economy.withdrawOnce(player,"money",40,BUY,"claim-2").isCompletedExceptionally());
        assertEquals(60,economy.balance(player,"money").balance());assertTrue(economy.withdrawOnce(player,"money",40,BUY,"claim-2").join().ok());
        assertEquals(60,economy.balance(player,"money").balance());assertEquals(60,service(store).balance(player,"money").balance());
    }
    @Test public void insufficientFundsAndMissingDebitCannotIssueRefund()throws Exception{
        Store store=new Store();UUID player=UUID.randomUUID();var economy=service(store);economy.createAccount(player,"Player");
        assertEquals(Status.INSUFFICIENT_FUNDS,economy.withdrawOnce(player,"money",1,BUY,"missing").join().status());
        assertTrue(economy.refundWithdrawalOnce(player,BUY,"missing",REFUND).isCompletedExceptionally());assertEquals(0,economy.balance(player,"money").balance());
    }
    @Test public void failedRefundCanBeRetriedWithoutDoubleCredit()throws Exception{
        Store store=new Store();UUID player=UUID.randomUUID();var economy=service(store);economy.createAccount(player,"Player");economy.deposit(player,"money",100,Cause.API);
        economy.withdrawOnce(player,"money",40,BUY,"refund-retry").join();
        store.failNext=true;assertTrue(economy.refundWithdrawalOnce(player,BUY,"refund-retry",REFUND).isCompletedExceptionally());
        assertEquals(100,economy.balance(player,"money").balance());
        economy.refundWithdrawalOnce(player,BUY,"refund-retry",REFUND).join();
        economy=service(store);economy.refundWithdrawalOnce(player,BUY,"refund-retry",REFUND).join();assertEquals(100,economy.balance(player,"money").balance());
        assertTrue(economy.refundWithdrawalOnce(player,BUY,"refund-retry",Cause.of("refund","other-plugin")).isCompletedExceptionally());
    }
    @Test public void oldCreditReceiptsRemainCompatibleButCannotBeUsedAsDebits()throws Exception{
        Store store=new Store();UUID player=UUID.randomUUID();var economy=service(store);economy.createAccount(player,"Player");economy.depositOnce(player,"money",20,BUY,"old-reward").join();
        store.docs.get(Codec.accountKey(player)).getAsJsonObject("receipts").entrySet().forEach(e->e.getValue().getAsJsonObject().remove("direction"));
        economy=service(store);assertTrue(economy.depositOnce(player,"money",20,BUY,"old-reward").join().ok());assertEquals(20,economy.balance(player,"money").balance());
        assertTrue(economy.withdrawOnce(player,"money",20,BUY,"old-reward").isCompletedExceptionally());
    }
}
