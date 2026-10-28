package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.*;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-DRS-26 (suspect 13): the recon blotter carries a bond's 6-dp fraction of par, not 3 dp. */
class ClusterReconPriceTest {
  @Test void blotterKeepsEverySixDecimalTickAndExactThreeDecimalPricesRenderAsBefore() throws Exception {
    var recon=new ClusterRecon(Files.createTempDirectory("recon-px").toFile(),"unused","test",10,10,10);
    long[] ticks={996_650L, 959_560L, 991_230L, 1L, 100_123_456L, 120_000_000L, 0L};
    for (int i=0;i<ticks.length;i++) { recon.onLiveOutput(trade(i+1,ticks[i]),"T"+i); }
    List<String> prices=recon.liveSince(0,10).stream().map(r->r.price().toPlainString()).toList();
    // the yu16-bond-position seeds (SQL holds 0.996650 / 0.959560 / 0.991230), then the edges
    assertEquals(List.of("0.99665","0.95956","0.99123","0.000001","100.123456","120.000","0.000"),prices);
    assertEquals(0,new BigDecimal("0.996650").compareTo(recon.liveSince(0,1).get(0).price()));
  }

  private static OutputEvent trade(long seq,long px){
    var out=new OutputEvent();
    out.kind=OutputEvent.KIND_TRADE_BOOKED;out.tradeSeq=seq;out.inputSeq=seq;out.accountId=22214;out.side=0;
    out.tradeQty=100_000;out.tradePx=px;out.orderRef=(int)seq;
    return out;
  }
}
