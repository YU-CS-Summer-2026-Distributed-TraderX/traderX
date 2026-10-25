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
        out.put("trades", Map.of("backlog", t.backlog(), "dropped", t.dropped(),
            "missingTradeSides", t.gaps().missing(), "lastTradeSeq", t.gaps().last(),
            "firstTradeSeqSinceStart", t.gaps().firstSeen()));
      } else if (s instanceof OrderFeedHandler o) {
        out.put("orders", Map.of("backlog", o.backlog(), "dropped", o.dropped(), "rejected", o.rejected()));
      }
    }
    return out;
  }
}
