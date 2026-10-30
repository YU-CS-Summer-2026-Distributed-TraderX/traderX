package finos.traderx.tradeprocessor.controller;

import finos.traderx.messaging.Subscriber;
import finos.traderx.tradeprocessor.OrderFeedHandler;
import finos.traderx.tradeprocessor.TradeFeedHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * YU18: read-only health of the two live projection feeds - how far behind (backlog), what this
 * process dropped (queue full), and how many trade sides never arrived at all (tradeSeq gaps).
 * A projection that silently falls behind the cluster is the failure this exists to make visible.
 * {@code applied} counts what reached the projection (trades) or the view (orders) in either feed
 * mode, so a rate can be read without SQL.
 */
@RestController
public final class ProjectionFeedController {
  private final List<Subscriber<?>> subscribers;

  public ProjectionFeedController(List<Subscriber<?>> subscribers) { this.subscribers = subscribers; }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private finos.traderx.tradeprocessor.service.TradeSnapshotStore snapshotStore;
  @org.springframework.beans.factory.annotation.Value("${settlement.t-plus-days:1}")
  private int settlementTPlusDays = 1;

  /**
   * YU18 trade store: settlement on the LEDGER (downstream-at-400k.md §7.6). With the snapshot projection,
   * SQL `trades` is a window, so how many booked sides have settled is read from the per-trade-date side
   * counts committed with each partition's cursor. {@code asOf} (ISO instant) defaults to now.
   */
  @GetMapping("/v2/projection-feed/settlement")
  public Map<String, Object> settlement(@org.springframework.web.bind.annotation.RequestParam(required = false) String asOf) {
    for (Subscriber<?> s : subscribers) {
      if (s instanceof TradeFeedHandler t && t.ledgerMode() && snapshotStore != null) {
        return snapshotStore.ledgerSettlement(t.snapshotScope(), settlementTPlusDays,
            asOf == null ? java.time.Instant.now() : java.time.Instant.parse(asOf));
      }
    }
    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
        "settlement on the ledger needs TRADE_PROJECTION_MODE=snapshot with PROJECTION_FEED_MODE=jetstream");
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private finos.traderx.tradeprocessor.service.OrderViewSnapshotStore orderSnapshots;

  /**
   * FR-DRS-29/30: what the snapshot read model has COMMITTED, per partition, next to the stream it was read from, so a
   * restore gate can check exactly-once in O(P): each trade partition's cursor, side count, highest tradeSeq, wrapped
   * sums and refused duplicates; each order partition's cursor and committed open orders; and per partition subject
   * the stream's last sequence (a committed cursor below it means unprojected messages). Committed, not live: it is
   * what a restart resumes from, and it is the same whichever replica answers.
   */
  @GetMapping("/v2/projection-feed/accounting")
  public Map<String, Object> accounting() throws Exception {
    TradeFeedHandler trades = null;
    OrderFeedHandler orders = null;
    for (Subscriber<?> s : subscribers) {
      if (s instanceof TradeFeedHandler t) { trades = t; } else if (s instanceof OrderFeedHandler o) { orders = o; }
    }
    if (trades == null || !trades.ledgerMode() || snapshotStore == null) {
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
          "accounting needs TRADE_PROJECTION_MODE=snapshot with PROJECTION_FEED_MODE=jetstream");
    }
    var nc = trades.natsConnection();
    int p = trades.partitionCount();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("scope", trades.snapshotScope());
    out.put("partitions", p);
    Map<String, Object> streams = new LinkedHashMap<>();   // FR-DRS-32: one stream, or one per partition
    for (int i = 0; i < p; i++) {
      String name = trades.streamName(i);
      if (streams.containsKey(name)) { continue; }
      var state = nc.jetStreamManagement().getStreamInfo(name).getStreamState();
      streams.put(name, Map.of("first", state.getFirstSequence(), "last", state.getLastSequence()));
    }
    out.put("streams", streams);
    Map<Integer, Map<String, Object>> committed = snapshotStore.committedAccounting(trades.snapshotScope());
    Map<String, Object> tp = new LinkedHashMap<>();
    for (int i = 0; i < p; i++) {
      Map<String, Object> m = new LinkedHashMap<>(committed.getOrDefault(i, Map.of("cursor", 0L, "sides", 0L, "maxSeq", 0L,
          "sumSeq", "0", "sumSq", "0", "duplicates", 0L, "epoch", "")));
      m.put("stream", trades.streamName(i));
      m.put("streamLast", finos.traderx.tradeprocessor.JetStreamFeed.lastSeq(nc, trades.streamName(i),
          finos.traderx.tradeprocessor.JetStreamFeed.subject("/trades", i, p)));
      tp.put(String.valueOf(i), m);
    }
    out.put("trades", tp);
    if (orders != null && orderSnapshots != null && orders.viewScope() != null) {
      Map<Integer, Map<String, Object>> oc = orderSnapshots.committed(orders.viewScope());
      Map<String, Object> op = new LinkedHashMap<>();
      for (int i = 0; i < p; i++) {
        Map<String, Object> m = new LinkedHashMap<>(oc.getOrDefault(i, Map.of("cursor", -1L, "open", 0L, "resting", 0L)));
        m.put("streamLast", finos.traderx.tradeprocessor.JetStreamFeed.lastSeq(nc, orders.streamName(i),
            finos.traderx.tradeprocessor.JetStreamFeed.subject("/orders", i, p)));
        op.put(String.valueOf(i), m);
      }
      out.put("orders", op);
    }
    return out;
  }

  @GetMapping("/v2/projection-feed")
  public Map<String, Object> status() {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Subscriber<?> s : subscribers) {
      if (s instanceof TradeFeedHandler t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", t.feedMode());
        m.put("backlog", t.backlog());
        m.put("dropped", t.dropped());
        m.put("applied", t.applied());
        m.put("missingTradeSides", t.gaps().missing());
        m.put("lastTradeSeq", t.gaps().last());
        m.put("firstTradeSeqSinceStart", t.gaps().firstSeen());
        m.put("projection", t.projectionMode());
        if (!t.views().isEmpty()) {
          m.put("snapshots", t.snapshots());
          m.put("snapshotRows", t.snapshotRows());
          m.put("snapshotFailures", t.snapshotFailures());
          Map<String, Object> parts = new LinkedHashMap<>();
          t.views().forEach((p, v) -> parts.put(String.valueOf(p), Map.of("streamSeq", v.streamSeq(), "applied", v.applied(),
              "positions", v.positionCount(), "dirty", v.dirtyCount(), "duplicates", v.accounting().duplicates())));
          m.put("partitions", parts);
        }
        out.put("trades", m);
      } else if (s instanceof OrderFeedHandler o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", o.feedMode());
        m.put("persist", o.persists());
        m.put("backlog", o.backlog());
        m.put("dropped", o.dropped());
        m.put("rejected", o.rejected());
        m.put("applied", o.view().applied());
        m.put("ignoredStale", o.view().ignored());
        m.put("viewSize", o.view().size());
        if (o.viewScope() != null) {
          m.put("resting", o.view().resting());
          m.put("viewSnapshots", o.viewSnapshotCount());
          m.put("viewSnapshotFailures", o.viewSnapshotFailureCount());
          m.put("committed", o.viewCommitted());
        }
        out.put("orders", m);
      }
    }
    return out;
  }
}
