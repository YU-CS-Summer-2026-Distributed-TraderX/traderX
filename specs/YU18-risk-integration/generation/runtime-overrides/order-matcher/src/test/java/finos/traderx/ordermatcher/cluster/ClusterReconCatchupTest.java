package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.*;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

/** The automatic catch-up chain must be a function of the committed log only. The live run
 * that motivated this refused its own cursor because the trade envelope's "date" is the
 * publish wall clock, so two replays of the same log hashed differently. */
class ClusterReconCatchupTest {
  @Test void chainedFormIsIndependentOfPublishTimeAndKeepsExactPayload() throws Exception {
    Path file=Files.createTempDirectory("apr").resolve(RunDescriptor.FILE);
    Files.writeString(file,"{\"schema\":\"traderx.run.v1\",\"epoch\":\"aprtest\",\"eventIdScheme\":\"epoch-v1\","
      +"\"storageLineage\":\"apr-storage\",\"projectionScope\":\"apr-scope\",\"adoptionEvidenceSha256\":null}");
    RunDescriptor d=RunDescriptor.read(file);
    OutputEvent out=new OutputEvent();
    out.kind=OutputEvent.KIND_TRADE_BOOKED;out.inputSeq=7;out.tradeSeq=3;out.accountId=22214;out.side=0;out.tradeQty=5;
    out.tradePx=100_123_456L;out.orderRef=9;
    String first=new String(TradeNatsPublisher.projectionEvent(out,"IBM",d),StandardCharsets.UTF_8);
    Thread.sleep(5);
    String second=new String(TradeNatsPublisher.projectionEvent(out,"IBM",d),StandardCharsets.UTF_8);
    assertNotEquals(first,second,"precondition: the raw envelope carries the publish clock");
    String a=ClusterRecon.canonicalEvent("TradeOrder",first),b=ClusterRecon.canonicalEvent("TradeOrder",second);
    assertEquals(a,b);
    assertEquals(ClusterRecon.chain(ClusterRecon.CATCHUP_GENESIS,a),ClusterRecon.chain(ClusterRecon.CATCHUP_GENESIS,b));
    assertTrue(first.contains(a.substring(a.indexOf("\"payload\":"),a.length()-1)),"payload bytes are copied exactly");
    assertTrue(a.startsWith("{\"type\":\"TradeOrder\",\"payload\":{"));
    assertThrows(IllegalStateException.class,()->ClusterRecon.canonicalEvent("OrderUpdate",first));
  }
}
