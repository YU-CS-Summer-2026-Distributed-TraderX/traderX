package finos.traderx.tradeprocessor.service;

import finos.traderx.tradeprocessor.model.OrderUpdate;
import java.math.BigDecimal;
import java.util.*;

/** FR-DRS-28 test stream: deterministic order lifecycles and an adversarial delivery of them. */
final class OrderBatchStream {
  private OrderBatchStream() {}
  static final String SCOPE="batch-scope", EPOCH="batch";

  static OrderUpdate managed(int ref,long seq,int ordinal) {
    OrderUpdate u=new OrderUpdate();
    u.setId(EPOCH+"-"+ref);u.setProjectionScope(SCOPE);u.setClusterEpoch(EPOCH);u.setEventIdScheme("epoch-v1");
    u.setRunDescriptorHash("a".repeat(64));u.setConsensusSequence(seq);u.setOutputOrdinal(ordinal);
    return u;
  }

  /**
   * A deterministic lifecycle per order, in engine order: NEW (with trace id and client key), zero
   * or more partial fills, then FILLED or CANCELED; some commands emit several outputs (a trailing
   * stop's ratchets share one sequence). Every 13th order is in the legacy scope, which has no
   * sequence and is last-write-wins.
   */
  static List<OrderUpdate> lifecycles(long seed,int count) { return lifecycles(seed,count,13); }

  /** {@code legacyEvery}: every n-th order in the legacy scope; 1 = all (a runtime with no run
   *  descriptor, as the CompSci render runs today), 0 = none. */
  static List<OrderUpdate> lifecycles(long seed,int count,int legacyEvery) {
    Random rnd=new Random(seed);
    List<OrderUpdate> out=new ArrayList<>();
    long seq=1000;
    for (int ref=1;ref<=count;ref++) {
      boolean legacy=legacyEvery>0 && ref%legacyEvery==0;
      int qty=10*(1+rnd.nextInt(20));
      int account=1001+rnd.nextInt(5);
      String side=rnd.nextBoolean()?"Buy":"Sell";
      String security=new String[]{"IBM","MSFT","AAPL"}[rnd.nextInt(3)];
      BigDecimal limit=rnd.nextInt(10)==0 ? null : new BigDecimal("100.125").add(BigDecimal.valueOf(rnd.nextInt(500),3));
      int kind=rnd.nextInt(10);   // 0: trailing stop, 1: stop, else limit/market
      long created=1_700_000_000_000L+ref*7L;
      int remaining=qty;
      int steps=rnd.nextInt(4);
      for (int step=0;step<=steps+1;step++) {
        seq+=1+rnd.nextInt(3);
        int outputs=kind==0 && step>0 && step<=steps ? 1+rnd.nextInt(3) : 1;
        for (int ordinal=0;ordinal<outputs;ordinal++) {
          OrderUpdate u=legacy ? new OrderUpdate() : managed(ref,seq,ordinal);
          if (legacy) {u.setId("legacy-"+ref);}
          u.setAccountId(account);u.setSecurity(security);u.setSide(side);u.setQuantity(qty);u.setLimitPrice(limit);
          u.setCreatedAt(created);u.setUpdatedAt(created+step*11L+ordinal);
          if (kind==0) {u.setOrderType("TRAILING_STOP");u.setTimeInForce("GTC");
            u.setTrailAmount(new BigDecimal("0.500"));u.setStopPrice(new BigDecimal("99.000").add(BigDecimal.valueOf(step*10+ordinal,2)));
            u.setTriggered(step>steps);}
          else if (kind==1) {u.setOrderType("STOP");u.setTimeInForce("DAY");u.setStopPrice(new BigDecimal("98.500"));u.setSessionDate(20261029);}
          if (step==0) {
            u.setStatus("NEW");u.setRemainingQuantity(qty);
            u.setTraceId(String.format("%032x",ref*7919L));u.setClientOrderKey("k"+ref);
          } else if (step<=steps) {
            int fill=Math.max(1,remaining/(2+rnd.nextInt(3)));
            if (ordinal==0) {remaining-=fill;}
            u.setStatus("PARTIALLY_FILLED");u.setRemainingQuantity(remaining);
            u.setLastFillQuantity(fill);u.setLastExecutionPrice(new BigDecimal("100.250"));
          } else if (rnd.nextInt(3)==0) {
            u.setStatus("CANCELED");u.setRemainingQuantity(0);u.setReason("USER_CANCELED");
          } else {
            u.setStatus("FILLED");u.setLastFillQuantity(remaining);remaining=0;u.setRemainingQuantity(0);
            u.setLastExecutionPrice(new BigDecimal("100.375"));
          }
          out.add(u);
        }
      }
    }
    return out;
  }

  /**
   * Engine order, made adversarial for delivery: lifecycles interleave by sequence, windows of up to
   * 24 updates are shuffled (later states overtake earlier ones, across batch boundaries), and about
   * one update in nine is delivered again later (at-least-once redelivery).
   */
  static List<OrderUpdate> adversarial(long seed,int count) {
    Random rnd=new Random(seed+1);
    List<OrderUpdate> all=lifecycles(seed,count);
    all.sort(Comparator.comparing((OrderUpdate u)->u.getConsensusSequence()==null ? u.getUpdatedAt()/7 : u.getConsensusSequence()));
    List<OrderUpdate> out=new ArrayList<>(all);
    for (int i=0;i<out.size();i+=1+rnd.nextInt(24)) {
      Collections.shuffle(out.subList(i,Math.min(out.size(),i+1+rnd.nextInt(24))),rnd);
    }
    for (int i=out.size()-1;i>0;i--) {
      if (rnd.nextInt(9)==0) {out.add(Math.min(out.size(),i+1+rnd.nextInt(3000)),out.get(i));}
    }
    return out;
  }

  static List<List<OrderUpdate>> batches(List<OrderUpdate> stream,int size) {
    List<List<OrderUpdate>> out=new ArrayList<>();
    for (int i=0;i<stream.size();i+=size) {out.add(stream.subList(i,Math.min(stream.size(),i+size)));}
    return out;
  }

}
