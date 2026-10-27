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
              "positions", v.positionCount(), "dirty", v.dirtyCount())));
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
        out.put("orders", m);
      }
    }
    return out;
  }
}
