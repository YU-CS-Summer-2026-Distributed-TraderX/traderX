package finos.traderx.tradeprocessor;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.api.PublishAck;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FR-DRS-48 (ADR-084 amendment, FR-FFR-14): fills routed to the gateway that owns the order.
 *
 * <p>Every gateway used to read the whole order feed to report the fills of its own FIX orders: at 450K orders/s that is
 * ~400 MB/s per gateway, 24x on the campaign's count, and a per-account subject cannot fix it because a batched bridge
 * message (FR-DRS-41) carries one subject and many accounts. Instead the booking event names the gateway that
 * submitted the order ({@code gateway}, the cluster session's principal), the order view remembers it with the order,
 * and this router republishes each update of such an order to {@code /fills.<gateway>} on a small stream
 * ({@code TRADERX_FILLS}), batched per gateway per view batch in the order's own order, in the same record form the
 * capture carries (one message, newline-separated events). A gateway reads only its own subject.
 *
 * <p>An update of an order whose booking event this replica never held (evicted, or booked before its first message
 * after a restart with no snapshot of it) is not routed; the gateway's per-order check reports that as a gap
 * (ADR-084 §7). Every partition's updates of one order come to the one replica that owns the partition (FR-DRS-45),
 * so an order's routed events are in order on its subject.
 */
final class FillsRouter {
  private static final Logger log = LoggerFactory.getLogger(FillsRouter.class);
  static final String SUBJECT_PREFIX = "/fills.";
  /** FR-DRS-53: with S > 1 fills streams, stream {@code <stream>_<k>} captures {@code /fillsk.<k>.>}. A prefix of its
   *  own: {@code /fills.<k>.>} would overlap a single stream's {@code /fills.>}, which NATS refuses. */
  static final String SHARD_PREFIX = "/fillsk.";
  private final JetStream js;
  private final String stream;
  private final int shards;
  private final AtomicLong routed = new AtomicLong();     // events
  private final AtomicLong messages = new AtomicLong();
  private final AtomicLong acked = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();
  private final Map<String, AtomicLong> byGateway = new ConcurrentHashMap<>();

  /** Creates (or brings to its limits) the fills stream on the server tagged {@code tag} (FR-DRS-52) and binds to it. */
  FillsRouter(Connection nc, String stream, long maxBytes, Duration maxAge, String tag) throws Exception {
    this(nc, stream, maxBytes, maxAge, tag, 1, null);
  }

  /**
   * FR-DRS-53: {@code shards} > 1 creates that many streams, {@code <stream>_<k>}, each holding its share of the bytes
   * and placed on tag {@code k mod n} of {@code placement} as the capture partitions are (FR-DRS-44). One stream read by
   * every gateway's filtered consumer was run 5's ceiling: ~6.4K msgs/s in, ~2.6K/s out over 18 consumers on one server.
   */
  FillsRouter(Connection nc, String stream, long maxBytes, Duration maxAge, String tag, int shards, String placement) throws Exception {
    this.stream = stream;
    this.shards = Math.max(1, shards);
    if (this.shards == 1) {
      JetStreamFeed.ensureStreamOn(nc, stream, maxBytes, maxAge, tag, SUBJECT_PREFIX + ">");
    } else {
      for (int k = 0; k < this.shards; k++) {
        JetStreamFeed.ensureStreamOn(nc, stream + "_" + k, maxBytes / this.shards, maxAge, JetStreamFeed.placementTag(placement, k),
            SHARD_PREFIX + k + ".>");
      }
    }
    this.js = nc.jetStream();
  }

  static String subject(String gateway) { return SUBJECT_PREFIX + gateway; }

  /**
   * FR-DRS-53: the gateway's stream of {@code shards}: the one its name claims ({@code <pod>_s<k>}, FR-DRS-55), else
   * floorMod(String.hashCode, S). The gateway computes the same (ClusterGatewayMain.fillsShard, FillsShardClaim.claimed).
   */
  static int shard(String gateway, int shards) {
    int at = gateway.lastIndexOf("_s");
    if (at > 0 && at + 2 < gateway.length()) {
      int k = 0; boolean digits = true;
      for (int i = at + 2; i < gateway.length() && digits; i++) {
        char c = gateway.charAt(i);
        digits = c >= '0' && c <= '9' && k <= 100_000;
        if (digits) k = k * 10 + (c - '0');
      }
      if (digits && k < shards) return k;
    }
    return Math.floorMod(gateway.hashCode(), shards);
  }

  static String subject(String gateway, int shards) {
    return shards <= 1 ? subject(gateway) : SHARD_PREFIX + shard(gateway, shards) + "." + gateway;
  }

  /** The records of one view batch grouped by the gateway of their order; one message per gateway, in order. */
  static final class Batch {
    private final Map<String, List<byte[]>> perGateway = new java.util.LinkedHashMap<>();
    void add(String gateway, byte[] record) { perGateway.computeIfAbsent(gateway, k -> new ArrayList<>()).add(record); }
    boolean isEmpty() { return perGateway.isEmpty(); }
    static byte[] join(List<byte[]> records) {
      int n = records.size() - 1;
      for (byte[] r : records) { n += r.length; }
      byte[] out = new byte[Math.max(0, n)]; int at = 0;
      for (int i = 0; i < records.size(); i++) {
        if (i > 0) { out[at++] = '\n'; }
        System.arraycopy(records.get(i), 0, out, at, records.get(i).length); at += records.get(i).length;
      }
      return out;
    }
  }

  /** On the partition's own thread: publish the batch, one message per gateway; acks are counted as they land. */
  void route(Batch batch) {
    for (Map.Entry<String, List<byte[]>> e : batch.perGateway.entrySet()) {
      byte[] body = Batch.join(e.getValue());
      routed.addAndGet(e.getValue().size());
      messages.incrementAndGet();
      byGateway.computeIfAbsent(e.getKey(), k -> new AtomicLong()).addAndGet(e.getValue().size());
      CompletableFuture<PublishAck> f;
      try {
        f = js.publishAsync(subject(e.getKey(), shards), body);
      } catch (RuntimeException x) {
        failed.incrementAndGet();
        log.warn("fills router: publish to {} refused: {}", subject(e.getKey(), shards), x.toString());
        continue;
      }
      f.whenComplete((ack, x) -> { if (x == null && ack != null && !ack.hasError()) { acked.incrementAndGet(); } else { failed.incrementAndGet(); } });
    }
  }

  Map<String, Object> stats() {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("stream", stream);
    m.put("streams", shards);
    m.put("routed", routed.get());
    m.put("messages", messages.get());
    m.put("acked", acked.get());
    m.put("failed", failed.get());
    Map<String, Long> g = new java.util.TreeMap<>();
    byGateway.forEach((k, v) -> g.put(k, v.get()));
    m.put("gateways", g);
    return m;
  }

  static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
