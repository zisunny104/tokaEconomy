package dev.toka.economy;

import com.google.gson.JsonObject;
import dev.toka.economy.core.*;
import org.junit.Test;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class DurableRewardTest {
    private static final Cause REWARD = Cause.of("reward", "tokaRewards");
    private static final class Store implements Persist {
        final Map<String,JsonObject> docs = new HashMap<>();
        CompletableFuture<Void> pending;
        Map<String,JsonObject> batch;
        public void save(Map<String,JsonObject> values,List<String> deletes){values.forEach((k,v)->docs.put(k,v.deepCopy()));deletes.forEach(docs::remove);}
        public CompletableFuture<Void> saveConfirmed(Map<String,JsonObject> values){ batch=values; return pending=new CompletableFuture<>(); }
        void succeed(){save(batch,List.of());pending.complete(null);}
    }
    private EconomyService service(Store store)throws Exception{return new EconomyService(TestSupport.settings(),store,Clock.systemUTC(),(id,node)->false,store.docs);}
    @Test public void completionWaitsForDurabilityAndRepeatsSurviveRestart()throws Exception{
        Store store=new Store(); UUID uuid=UUID.randomUUID(); EconomyService economy=service(store);economy.createAccount(uuid,"Player");
        var first=economy.depositOnce(uuid,"money",50,REWARD,"daily:2026-10-20");
        var same=economy.depositOnce(uuid,"money",50,REWARD,"daily:2026-10-20");
        assertFalse(first.isDone());assertFalse(same.isDone());assertEquals(50,economy.balance(uuid,"money").balance());
        store.succeed();assertTrue(first.join().ok());assertTrue(same.join().ok());
        EconomyService restarted=service(store);
        assertTrue(restarted.depositOnce(uuid,"money",50,REWARD,"daily:2026-10-20").join().ok());
        assertEquals(50,restarted.balance(uuid,"money").balance());
        assertTrue(restarted.depositOnce(uuid,"money",51,REWARD,"daily:2026-10-20").isCompletedExceptionally());
    }
    @Test public void failedWriteRetryNeverCreditsTwiceAndOtherWritesKeepTheReceipt()throws Exception{
        Store store=new Store();UUID uuid=UUID.randomUUID();EconomyService economy=service(store);economy.createAccount(uuid,"Player");
        var first=economy.depositOnce(uuid,"money",75,REWARD,"op-1");store.pending.completeExceptionally(new java.io.IOException("disk"));
        assertTrue(first.isCompletedExceptionally());
        economy.deposit(uuid,"money",5,Cause.API); // Carries the same receipt atomically with the new balance.
        EconomyService restarted=service(store);
        assertTrue(restarted.depositOnce(uuid,"money",75,REWARD,"op-1").join().ok());
        assertEquals(80,restarted.balance(uuid,"money").balance());
        var retry=economy.depositOnce(uuid,"money",75,REWARD,"op-1");store.succeed();assertTrue(retry.join().ok());
        assertEquals(80,economy.balance(uuid,"money").balance());
    }
}
