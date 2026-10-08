package finos.traderx.ordermatcher.cluster;
import io.aeron.cluster.client.AeronCluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
/** Simulated outage gradients through the actual recovery policy; no live traffic or GKE sizing claim. */
@Timeout(60)
class FeedReconnectBudgetTest {
 @Test void actualPolicySweepsBudgetBackoffAttemptAndFailureGradients()throws Exception{
  int rows=0,successes=0,exhaustions=0;
  for(long budget:new long[]{1000,10000,20000})for(long delay:new long[]{50,100,250})for(long cap:new long[]{250,1000,2000})for(boolean silent:new boolean[]{false,true})for(long outage:new long[]{0,50,500,1500,4000,9000,10000,15000}){
   try(var f=new FeedReconnectTest.Fixture(new FeedAdapterMain.Retry(budget,delay,1000,cap))){
    f.quote=false;long[]successTime={-1};
    f.action=(ctx,call)->{
     if(call==1){var peer=f.peer(ctx,1);peer.onPoll=()->peer.connected.set(false);return peer.client;}
     long availableAt=TimeUnit.MILLISECONDS.toNanos(outage);long timeout=ctx.messageTimeoutNs();
     long handshake=TimeUnit.MILLISECONDS.toNanos(5);
     if(f.clock.now<availableAt){
      long wait=silent?Math.min(timeout,availableAt-f.clock.now):Math.min(timeout,handshake);f.clock.now+=wait;timeout-=wait;
      if(!silent||f.clock.now<availableAt||timeout<handshake){f.clock.now+=silent?timeout:0;throw new java.net.UnknownHostException(silent?"simulated silent endpoint timeout":"simulated fast DNS refusal");}
     }
     if(timeout<handshake){f.clock.now+=timeout;throw new IllegalStateException("simulated handshake budget");}
     f.clock.now+=handshake;successTime[0]=f.clock.now;return f.peer(ctx,2).client;
    };
    f.clock.stop=()->f.peers.size()>1;f.run();var status=f.status();boolean recovered=status.get("recoveries").asLong()==1;
    if(recovered){successes++;assertTrue(successTime[0]<TimeUnit.MILLISECONDS.toNanos(budget));}
    else{exhaustions++;assertEquals("EXHAUSTED",status.get("state").asText());assertTrue(f.clock.now>=TimeUnit.MILLISECONDS.toNanos(budget));}
    if(outage>=budget)assertFalse(recovered,"peer not available inside incident budget");
    assertTrue(f.calls>=2);for(int i=1;i<f.caps.size();i++){assertTrue(f.caps.get(i)>0);assertTrue(f.caps.get(i)<=TimeUnit.MILLISECONDS.toNanos(Math.min(cap,budget)));}
    f.clean(f.peers.size());rows++;
    System.out.println("BUDGET_MATRIX {\"budgetMs\":"+budget+",\"backoffMs\":"+delay+",\"attemptMs\":"+cap+",\"outageMs\":"+outage+",\"mode\":\""+(silent?"silent":"dns_fast")+"\",\"recovered\":"+recovered+",\"attempts\":"+(f.calls-1)+",\"elapsedMs\":"+TimeUnit.NANOSECONDS.toMillis(recovered?successTime[0]:f.clock.now)+"}");
   }
  }
  assertEquals(432,rows);assertTrue(successes>0);assertTrue(exhaustions>0);System.out.println("BUDGET_SUMMARY rows="+rows+" success="+successes+" exhaustion="+exhaustions);
 }
}
