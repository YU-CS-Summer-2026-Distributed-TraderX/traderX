import io.nats.client.*;
import io.nats.client.api.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Load for the history-store bake-off: ORDERS orders/s into TRADERX_FEED on the bridges' exact managed wire
 * (TradeNatsPublisher / OrderNatsPublisher encode), partition subjects {@code /trades.<acct mod P>},
 * {@code /orders.<acct mod P>}.
 *
 * <p>One cross = two orders = 2 trade sides + 3 order updates (NEW for the resting order, FILLED for both), i.e.
 * 1 trade side and 1.5 updates per order, the mix downstream-at-400k.md §1 sizes. 400K orders/s = 1M msgs/s.
 *
 * <p>JetStream-acked async publish with a bounded window per thread, so the stream holds exactly what the load
 * counts as acked (the exactly-once reference); if NATS cannot take the rate the achieved rate is printed, never
 * silently dropped.
 *
 * <p>Args (key=value): url, orders (per second), seconds, total (orders; fill flat out instead of pacing), threads, partitions, accounts, securities, window,
 * stream, maxBytesGiB, maxAgeHours (2, as TRADERX_FEED), streams (1, or P: one stream per partition), create (true: delete+recreate).
 */
public final class HsbLoad {
  static final String EPOCH = "hsb20261029";
  static final String SCOPE = "run-" + EPOCH;
  static final String HEX0 = "0000000000000000";
  static final String HASH = "9f2c1e0b7a6d5c4b3a29180f7e6d5c4b3a29180f7e6d5c4b3a29180f7e6d5c4b";

  public static void main(String[] a) throws Exception {
    Map<String, String> o = args(a);
    String url = o.getOrDefault("url", "nats://127.0.0.1:4222");
    long orders = Long.parseLong(o.getOrDefault("orders", "400000"));
    int seconds = Integer.parseInt(o.getOrDefault("seconds", "600"));
    int threads = Integer.parseInt(o.getOrDefault("threads", "8"));
    int parts = Integer.parseInt(o.getOrDefault("partitions", "4"));
    int accounts = Integer.parseInt(o.getOrDefault("accounts", "10000"));
    int secs = Integer.parseInt(o.getOrDefault("securities", "500"));
    int window = Integer.parseInt(o.getOrDefault("window", "8192"));
    String stream = o.getOrDefault("stream", "TRADERX_FEED");
    int streams = Integer.parseInt(o.getOrDefault("streams", "1"));
    if (Boolean.parseBoolean(o.getOrDefault("create", "false"))) {
      try (Connection nc = Nats.connect(url)) {
        JetStreamManagement jsm = nc.jetStreamManagement();
        for (String n : jsm.getStreamNames()) if (n.startsWith(stream)) jsm.deleteStream(n);
        long cap = Long.parseLong(o.getOrDefault("maxBytesGiB", "800")) << 30;
        // TRADERX_FEED as JetStreamFeed.ensureStream creates it; only the byte cap is sized for a full-rate run.
        // streams=P shards it per partition (TRADERX_FEED_P<p> over /trades.<p>, /orders.<p>): one stream's ingest
        // path tops out near 250K acked msgs/s, a quarter of 400K orders/s.
        for (int p = 0; p < (streams == 1 ? 1 : parts); p++) {
          StreamConfiguration.Builder b = StreamConfiguration.builder().storageType(StorageType.File)
              .maxBytes(streams == 1 ? cap : cap / parts).maxAge(Duration.ofHours(Long.parseLong(o.getOrDefault("maxAgeHours", "2"))));
          if (streams == 1) b.name(stream).subjects("/trades", "/trades.*", "/orders", "/orders.*");
          else b.name(stream + "_P" + p).subjects("/trades." + p, "/orders." + p);
          jsm.addStream(b.build());
        }
        System.out.println("stream " + stream + " recreated, shards=" + (streams == 1 ? 1 : parts));
      }
    }
    // total=N: publish N orders as fast as the stream acks them (a fill), instead of pacing for `seconds`.
    long totalPerThread = Long.parseLong(o.getOrDefault("total", "0")) / 2 / threads;
    double crossesPerThreadPerNs = orders / 2.0 / threads / 1e9;
    AtomicLong acked = new AtomicLong(), failed = new AtomicLong(), crosses = new AtomicLong();
    long t0 = System.nanoTime(), end = totalPerThread > 0 ? Long.MAX_VALUE : t0 + seconds * 1_000_000_000L;
    List<Thread> ts = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      final int ti = t;
      Thread th = new Thread(() -> {
        try (Connection nc = Nats.connect(new Options.Builder().server(url).bufferSize(1 << 20)
            .maxMessagesInOutgoingQueue(1 << 20).build())) {
          JetStream js = nc.jetStream();
          Semaphore win = new Semaphore(window);
          SplittableRandom r = new SplittableRandom(7919L * (ti + 1));
          StringBuilder sb = new StringBuilder(1024);
          long done = 0;
          while (true) {
            long now = System.nanoTime();
            if (now >= end || (totalPerThread > 0 && done >= totalPerThread)) break;
            long due = totalPerThread > 0 ? Long.MAX_VALUE : (long) ((now - t0) * crossesPerThreadPerNs);
            if (done >= due) { Thread.onSpinWait(); continue; }
            long seq = ti + 1 + done * threads;     // global tradeSeq, unique across threads
            int buyer = r.nextInt(accounts) + 1, seller = r.nextInt(accounts) + 1;
            String sec = "S" + r.nextInt(secs);
            int qty = 1 + r.nextInt(1000);
            long px = (10 + r.nextInt(490)) * 1_000_000L + r.nextInt(100) * 10_000L;
            long ms = System.currentTimeMillis();
            long buyRef = 2 * seq, sellRef = 2 * seq - 1;
            pub(js, win, failed, acked, "/orders." + seller % parts,
                order(sb, sellRef, seller, sec, false, qty, qty, px, "NEW", 0, 0, ms - 1, ms - 1, seq * 4, 0));
            pub(js, win, failed, acked, "/trades." + buyer % parts, trade(sb, seq, true, buyer, sec, qty, px, buyRef, ms, seq * 4 + 1));
            pub(js, win, failed, acked, "/trades." + seller % parts, trade(sb, seq, false, seller, sec, qty, px, sellRef, ms, seq * 4 + 1));
            pub(js, win, failed, acked, "/orders." + buyer % parts,
                order(sb, buyRef, buyer, sec, true, qty, 0, px, "FILLED", px, qty, ms, ms, seq * 4 + 1, 1));
            pub(js, win, failed, acked, "/orders." + seller % parts,
                order(sb, sellRef, seller, sec, false, qty, 0, px, "FILLED", px, qty, ms - 1, ms, seq * 4 + 1, 2));
            done++;
            if ((done & 1023) == 0) crosses.addAndGet(1024);
          }
          crosses.addAndGet(done & 1023);
          win.acquire(window); // drain the window: every publish is acked or failed before we report
        } catch (Exception e) { e.printStackTrace(); }
      }, "load-" + t);
      th.start();
      ts.add(th);
    }
    long last = 0, lastT = System.nanoTime();
    while (ts.stream().anyMatch(Thread::isAlive)) {
      Thread.sleep(5000);
      long c = acked.get(), n = System.nanoTime();
      System.out.printf("%tT acked=%d (%.0f msgs/s) crosses=%d failed=%d%n", new Date(), c,
          (c - last) * 1e9 / (n - lastT), crosses.get(), failed.get());
      last = c; lastT = n;
    }
    for (Thread th : ts) th.join();
    double el = (System.nanoTime() - t0) / 1e9;
    System.out.printf("DONE crosses=%d orders=%d msgs_expected=%d acked=%d failed=%d elapsed=%.1fs avg=%.0f msgs/s%n",
        crosses.get(), crosses.get() * 2, crosses.get() * 5, acked.get(), failed.get(), el, acked.get() / el);
  }

  static void pub(JetStream js, Semaphore win, AtomicLong failed, AtomicLong acked, String subj, byte[] body) {
    win.acquireUninterruptibly();
    js.publishAsync(subj, body).whenComplete((ack, x) -> {
      if (x != null || ack == null || ack.hasError()) failed.incrementAndGet(); else acked.incrementAndGet();
      win.release();
    });
  }

  static String eventFields(long cseq) {
    return ",\"projectionScope\":\"" + SCOPE + "\",\"clusterEpoch\":\"" + EPOCH + "\",\"eventIdScheme\":\"e1\""
        + ",\"runDescriptorHash\":\"" + HASH + "\",\"consensusSequence\":" + cseq;
  }

  /** TradeNatsPublisher.encode, managed ids. */
  static byte[] trade(StringBuilder sb, long seq, boolean buy, int acct, String sec, int qty, long px, long ref, long ms, long cseq) {
    sb.setLength(0);
    sb.append("{\"topic\":\"/trades\",\"from\":\"cluster-bridge\",\"type\":\"TradeOrder\",\"date\":").append(ms)
      .append(",\"payload\":{\"id\":\"e1-").append(EPOCH).append('-').append(seq).append(buy ? "-B" : "-S")
      .append("\",\"state\":\"New\",\"security\":\"").append(sec).append("\",\"quantity\":").append(qty)
      .append(",\"price\":");
    px(sb, px);
    sb.append(",\"accountId\":").append(acct).append(",\"side\":\"").append(buy ? "Buy" : "Sell")
      .append("\",\"sourceOrderId\":\"").append(EPOCH).append('-').append(ref).append('"')
      .append(eventFields(cseq)).append("}}");
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** OrderNatsPublisher.encode for an untyped limit order with a trace id and (on NEW) a client key. */
  static byte[] order(StringBuilder sb, long ref, int acct, String sec, boolean buy, int qty, int remaining, long limitPx,
                      String status, long lastPx, int lastQty, long created, long updated, long cseq, int ordinal) {
    sb.setLength(0);
    sb.append("{\"topic\":\"/orders\",\"from\":\"cluster-bridge\",\"type\":\"OrderUpdate\",\"date\":").append(updated)
      .append(",\"payload\":{\"id\":\"").append(EPOCH).append('-').append(ref)
      .append("\",\"accountId\":").append(acct).append(",\"security\":\"").append(sec)
      .append("\",\"side\":\"").append(buy ? "Buy" : "Sell").append("\",\"quantity\":").append(qty)
      .append(",\"remainingQuantity\":").append(remaining).append(",\"limitPrice\":");
    px(sb, limitPx);
    sb.append(",\"status\":\"").append(status).append("\",\"lastExecutionPrice\":");
    px(sb, lastPx);
    sb.append(",\"lastFillQuantity\":").append(lastQty).append(",\"createdAt\":").append(created)
      .append(",\"updatedAt\":").append(updated)
      .append(",\"traceId\":\"").append(HEX0).append(Long.toHexString(ref * 0x9E3779B97F4A7C15L | 1L << 60)).append('"');
    if ("NEW".equals(status)) sb.append(",\"clientOrderKey\":\"").append(ref ^ 0x5DEECE66DL).append('"');
    sb.append(eventFields(cseq)).append(",\"outputOrdinal\":").append(ordinal).append("}}");
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  static void px(StringBuilder sb, long px) {
    sb.append(px / 1_000_000L).append('.');
    long frac = px % 1_000_000L;
    for (long div = 100_000L; div >= 1; div /= 10) sb.append((char) ('0' + (frac / div) % 10));
  }

  static Map<String, String> args(String[] a) {
    Map<String, String> m = new HashMap<>();
    for (String s : a) { int i = s.indexOf('='); m.put(s.substring(0, i), s.substring(i + 1)); }
    return m;
  }
}
