import io.nats.client.*;
import io.nats.client.api.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * History feed: TRADERX_FEED -> a full-rate history store (every trade side and every order event).
 *
 * <p>One thread per (feed, partition), each on a durable pull consumer {@code hs-<sink>-<feed>-p<p>}. Rows are
 * buffered up to {@code batch} rows or {@code flushMs}, written to the store in one atomic write, and the stream is
 * acked (AckPolicy.All) only up to the store's DURABLE watermark for that (table, partition).
 *
 * <p>Exactly once: every row carries its stream sequence {@code seq}. On start each thread reads the store's own
 * max(seq) for its (table, partition) and skips any delivered message at or below it, so a kill -9 between the
 * store's commit and the ack replays into a no-op. Per-partition delivery is in stream order, so the filter is exact.
 *
 * <p>Sinks: {@code clickhouse} (HTTP, TSV insert, MergeTree with fsync_after_insert), {@code questdb} (ILP over
 * HTTP, one request = one transaction per table, commit mode sync), {@code kdb} (q IPC into hsb.q's intraday
 * tables; the watermark advances only after hsb.q writes down and syncs).
 *
 * <p>Args (key=value): sink, name (durable prefix), url (NATS), store (host:port), stream, streams, partitions, batch, flushMs, fetch,
 * idleExit (s).
 */
public final class HistoryFeed {
  static final String[] FEEDS = {"trades", "orders"};
  /** Well above any write + write-down (kdb: 1 s). */
  static final Duration ACK_WAIT = Duration.ofSeconds(30);

  public static void main(String[] a) throws Exception {
    Map<String, String> o = new HashMap<>();
    for (String s : a) { int i = s.indexOf('='); o.put(s.substring(0, i), s.substring(i + 1)); }
    String sinkName = o.getOrDefault("sink", "clickhouse");
    String name = o.getOrDefault("name", sinkName);   // durable prefix hs-<name>-<feed>-p<p>
    String url = o.getOrDefault("url", "nats://127.0.0.1:4222");
    String store = o.getOrDefault("store", "127.0.0.1:8123");
    String stream = o.getOrDefault("stream", "TRADERX_FEED");
    int parts = Integer.parseInt(o.getOrDefault("partitions", "4"));
    int batch = Integer.parseInt(o.getOrDefault("batch", "100000"));
    int flushMs = Integer.parseInt(o.getOrDefault("flushMs", "500"));
    int fetch = Integer.parseInt(o.getOrDefault("fetch", "5000"));
    int idleExit = Integer.parseInt(o.getOrDefault("idleExit", "0"));
    boolean sharded = !o.getOrDefault("streams", "1").equals("1");   // HsbLoad streams=P: TRADERX_FEED_P<p>

    Stats stats = new Stats();
    List<Lane> lanes = new ArrayList<>();
    for (String feed : FEEDS) {
      for (int p = 0; p < parts; p++) {
        Sink sink = switch (sinkName) {
          case "clickhouse" -> new ClickHouse(store);
          case "questdb" -> new QuestDb(store);
          case "kdb" -> new Kdb(store);
          case "null" -> new Sink() {   // calibration: the feed's own read + parse ceiling, no store
            long last;
            public long resume(String f, int q) { return 0; }
            public long write(String f, int q, Batch x) { return last = x.seq[x.n - 1]; }
            public long durable(String f, int q) { return last; } };
          default -> throw new IllegalArgumentException(sinkName);
        };
        if (lanes.isEmpty()) sink.prepare();
        // A connection per lane: one connection's reader thread is a ceiling at ~1M msgs/s of JSON.
        Connection nc = Nats.connect(new Options.Builder().server(url).bufferSize(1 << 20).build());
        lanes.add(new Lane(nc, sharded ? stream + "_P" + p : stream, "hs-" + name + "-" + feed + "-p" + p, "/" + feed + "." + p, feed, p,
            sink, batch, flushMs, fetch, stats));
      }
    }
    lanes.forEach(l -> l.thread.start());
    long t0 = System.nanoTime(), last = 0, lastT = t0, idleSince = 0;
    while (true) {
      Thread.sleep(5000);
      long n = System.nanoTime(), rows = stats.rows.get(), pending = 0;
      for (Lane l : lanes) pending += l.pending();
      System.out.printf("%tT rows=%d (%.0f/s) skipped=%d writes=%d writeMsAvg=%.1f writeMsMax=%d pending=%d%n",
          new Date(), rows, (rows - last) * 1e9 / (n - lastT), stats.skipped.get(), stats.writes.get(),
          stats.writes.get() == 0 ? 0 : stats.writeNs.get() / 1e6 / stats.writes.get(), stats.writeMaxMs.getAndSet(0), pending);
      if (lanes.stream().anyMatch(l -> !l.thread.isAlive())) { System.out.println("LANE DIED"); System.exit(2); }
      if (idleExit > 0) {
        if (pending == 0 && rows == last) { if (idleSince == 0) idleSince = n; }
        else idleSince = 0;
        if (idleSince != 0 && n - idleSince > idleExit * 1_000_000_000L) {
          System.out.printf("DONE rows=%d skipped=%d elapsed=%.1fs%n", rows, stats.skipped.get(), (n - t0) / 1e9);
          System.exit(0);
        }
      }
      last = rows; lastT = n;
    }
  }

  static final class Stats {
    final AtomicLong rows = new AtomicLong(), skipped = new AtomicLong(), writes = new AtomicLong(),
        writeNs = new AtomicLong(), writeMaxMs = new AtomicLong();
  }

  /** One (feed, partition): fetch, parse, buffer, write, ack to the durable watermark. */
  static final class Lane implements Runnable {
    final Thread thread;
    final Connection nc; final String stream, durable, subject, feed; final int part, batchRows, flushMs, fetch;
    final Sink sink; final Stats stats;
    volatile JetStreamSubscription sub;

    Lane(Connection nc, String stream, String durable, String subject, String feed, int part, Sink sink,
         int batchRows, int flushMs, int fetch, Stats stats) {
      this.nc = nc; this.stream = stream; this.durable = durable; this.subject = subject; this.feed = feed;
      this.part = part; this.sink = sink; this.batchRows = batchRows; this.flushMs = flushMs; this.fetch = fetch;
      this.stats = stats;
      this.thread = new Thread(this, durable);
    }

    /** Undelivered + delivered-but-unacked: 0 only when the store holds, durably, everything in the subject. */
    long pending() {
      try { ConsumerInfo ci = sub.getConsumerInfo(); return ci.getNumPending() + ci.getNumAckPending(); }
      catch (Exception e) { return -1; }
    }

    public void run() {
      try {
        // The store is the cursor: it holds exactly this subject's messages up to max(seq), written in stream order.
        // A restart recreates the durable at cursor + 1, so delivery is in stream order by construction and the seq
        // filter below is exact. Resuming the old durable is NOT: its unacked window is redelivered interleaved with
        // new messages, and an AckPolicy.All ack of a later one retires earlier redeliveries never written (measured:
        // 19 of 360M order events lost). A cold create on a 150M-message shard costs NATS ~100 s of subject-index
        // loading (once per shard; 0 s warm), hence the long API timeout and no retry.
        long cursor = sink.resume(feed, part);
        JetStreamOptions jso = JetStreamOptions.builder().requestTimeout(Duration.ofMinutes(10)).build();
        JetStreamManagement jsm = nc.jetStreamManagement(jso);
        try { jsm.deleteConsumer(stream, durable); } catch (JetStreamApiException ignored) { }
        ConsumerConfiguration.Builder cc = ConsumerConfiguration.builder().durable(durable).filterSubject(subject)
            .ackPolicy(AckPolicy.All).ackWait(ACK_WAIT).maxAckPending(4_000_000);
        if (cursor == 0) cc.deliverPolicy(DeliverPolicy.All);
        else cc.deliverPolicy(DeliverPolicy.ByStartSequence).startSequence(cursor + 1);
        long t0 = System.nanoTime();
        jsm.addOrUpdateConsumer(stream, cc.build());
        sub = nc.jetStream(jso).subscribe(null, PullSubscribeOptions.bind(stream, durable));
        System.out.printf("%s consumer at seq %d ready in %.1f s%n", durable, cursor + 1, (System.nanoTime() - t0) / 1e9);
        System.out.printf("%s resume after seq %d%n", durable, cursor);
        Batch b = new Batch(feed.equals("trades"), batchRows);
        ArrayDeque<long[]> pendingSeq = new ArrayDeque<>();
        ArrayDeque<Message> pendingMsg = new ArrayDeque<>();
        Message lastFetched = null; long lastFetchedSeq = 0;
        long batchStart = 0;
        Parser parser = new Parser();
        while (true) {
          List<Message> msgs = sub.fetch(Math.min(fetch, batchRows - b.n), Duration.ofMillis(50));
          for (Message m : msgs) {
            long seq = m.metaData().streamSequence();
            lastFetched = m; lastFetchedSeq = seq;
            if (seq <= cursor) { stats.skipped.incrementAndGet(); continue; }   // a redelivery of a written row
            parser.parse(m.getData(), b, seq, part);
            cursor = seq;
            if (b.n == 1) batchStart = System.nanoTime();
          }
          boolean flush = b.n >= batchRows || (b.n > 0 && System.nanoTime() - batchStart >= flushMs * 1_000_000L);
          long durableSeq;
          if (flush) {
            long t = System.nanoTime();
            durableSeq = sink.write(feed, part, b);
            long ns = System.nanoTime() - t;
            stats.writes.incrementAndGet(); stats.writeNs.addAndGet(ns); stats.rows.addAndGet(b.n);
            stats.writeMaxMs.accumulateAndGet(ns / 1_000_000, Math::max);
            b.n = 0;
          } else if (b.n == 0 && (lastFetched != null || !pendingSeq.isEmpty())) {
            durableSeq = sink.durable(feed, part);   // idle or skip-only: let the ack catch up
          } else continue;
          if (lastFetched != null && b.n == 0) {          // everything fetched so far is in the store
            pendingSeq.add(new long[]{lastFetchedSeq}); pendingMsg.add(lastFetched); lastFetched = null;
          }
          Message ack = null;
          while (!pendingSeq.isEmpty() && pendingSeq.peek()[0] <= durableSeq) { pendingSeq.poll(); ack = pendingMsg.poll(); }
          if (ack != null) ack.ack();
          if (!pendingSeq.isEmpty() && b.n == 0 && msgs.isEmpty()) Thread.sleep(100); // waiting on a write-down
        }
      } catch (Exception e) {
        System.err.println(durable + " FAILED: " + e);
        e.printStackTrace();
      }
    }
  }

  /** Column buffers for one write. Trades: key=tradeSeq, ref=orderRef. Orders: key=orderRef, ref=clientOrderKey. */
  static final class Batch {
    final boolean trades; int n;
    final long[] ts, seq, key, ref, cseq, created; final int[] acct, qty, remaining, lastQty, ordinal;
    final byte[] side; final double[] px, lastPx; final String[] sec, status; short part;

    Batch(boolean trades, int cap) {
      this.trades = trades;
      ts = new long[cap]; seq = new long[cap]; key = new long[cap]; ref = new long[cap]; cseq = new long[cap];
      acct = new int[cap]; qty = new int[cap]; side = new byte[cap]; px = new double[cap]; sec = new String[cap];
      created = trades ? null : new long[cap]; remaining = trades ? null : new int[cap];
      lastQty = trades ? null : new int[cap]; ordinal = trades ? null : new int[cap];
      lastPx = trades ? null : new double[cap]; status = trades ? null : new String[cap];
    }
  }

  /** Flat scanner for the bridges' own JSON (no escapes, no nesting below payload). Ignores unknown keys. */
  static final class Parser {
    final HashMap<String, String> interned = new HashMap<>();
    byte[] d; int i;

    void parse(byte[] data, Batch b, long seq, int part) {
      d = data; i = 0;
      int r = b.n;
      b.seq[r] = seq; b.part = (short) part; b.ref[r] = 0;
      while (true) {
        int ks = indexOf('"', i);
        if (ks < 0) break;
        int ke = indexOf('"', ks + 1);
        String k = new String(d, ks + 1, ke - ks - 1, StandardCharsets.ISO_8859_1);
        i = ke + 2;                                   // past ":
        if (k.equals("payload")) { i++; continue; }   // past {
        switch (k) {
          case "date" -> b.ts[r] = num();
          case "id" -> { String s = str(); int dash = s.lastIndexOf('-');
            if (b.trades) { int d2 = s.lastIndexOf('-', dash - 1); b.key[r] = Long.parseLong(s, d2 + 1, dash, 10); b.side[r] = (byte) s.charAt(dash + 1); }
            else b.key[r] = Long.parseLong(s, dash + 1, s.length(), 10); }
          case "security" -> b.sec[r] = intern(str());
          case "quantity" -> b.qty[r] = (int) num();
          case "price", "limitPrice" -> b.px[r] = px();
          case "accountId" -> b.acct[r] = (int) num();
          case "side" -> { String s = str(); if (!b.trades) b.side[r] = (byte) s.charAt(0); }
          case "sourceOrderId" -> { String s = str(); b.ref[r] = Long.parseLong(s, s.lastIndexOf('-') + 1, s.length(), 10); }
          case "consensusSequence" -> b.cseq[r] = num();
          case "remainingQuantity" -> b.remaining[r] = (int) num();
          case "status" -> b.status[r] = intern(str());
          case "lastExecutionPrice" -> b.lastPx[r] = px();
          case "lastFillQuantity" -> b.lastQty[r] = (int) num();
          case "createdAt" -> b.created[r] = num();
          case "updatedAt" -> b.ts[r] = num();
          case "outputOrdinal" -> b.ordinal[r] = (int) num();
          case "clientOrderKey" -> b.ref[r] = Long.parseUnsignedLong(str());
          default -> skip();
        }
      }
      b.n++;
    }

    int indexOf(char c, int from) { for (int j = from; j < d.length; j++) if (d[j] == c) return j; return -1; }
    String intern(String s) { return interned.computeIfAbsent(s, x -> x); }
    String str() { int s = i + 1, e = indexOf('"', s); i = e + 1; return new String(d, s, e - s, StandardCharsets.ISO_8859_1); }
    long num() { long v = 0; boolean neg = d[i] == '-'; if (neg) i++; while (i < d.length && d[i] >= '0' && d[i] <= '9') v = v * 10 + (d[i++] - '0'); return neg ? -v : v; }
    double px() { long w = num(); if (d[i] != '.') return w; i++; long f = 0, sc = 1; while (d[i] >= '0' && d[i] <= '9') { f = f * 10 + (d[i++] - '0'); sc *= 10; } return w + (double) f / sc; }
    void skip() { if (d[i] == '"') str(); else while (i < d.length && d[i] != ',' && d[i] != '}') i++; }
  }

  interface Sink {
    default void prepare() throws Exception { }
    /** The store's max stored seq for (table, partition); 0 when empty. */
    long resume(String feed, int part) throws Exception;
    /** Write the batch atomically; return the durable watermark (max durable seq) for (table, partition). */
    long write(String feed, int part, Batch b) throws Exception;
    long durable(String feed, int part) throws Exception;
  }

  // ---------------------------------------------------------------- ClickHouse
  static final class ClickHouse implements Sink {
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final String base; long durable;
    ClickHouse(String store) { base = "http://" + store + "/"; }

    String q(String sql, byte[] body) throws Exception {
      HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(base + "?query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8)))
          .timeout(Duration.ofMinutes(10));
      HttpResponse<String> resp = http.send(body == null ? r.GET().build() : r.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
          HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) throw new IllegalStateException("clickhouse " + resp.statusCode() + ": " + resp.body());
      return resp.body().trim();
    }

    public void prepare() throws Exception {
      String set = " ENGINE=MergeTree PARTITION BY toDate(ts) ORDER BY (account, ts)"
          + " SETTINGS fsync_after_insert=1, fsync_part_directory=1";
      q("CREATE TABLE IF NOT EXISTS trades (ts DateTime64(3), seq UInt64, part UInt8, trade_seq UInt64, side FixedString(1),"
          + " account UInt32, security LowCardinality(String), qty UInt32, px Float64, order_ref UInt64, cseq UInt64)" + set, new byte[0]);
      q("CREATE TABLE IF NOT EXISTS orders (ts DateTime64(3), seq UInt64, part UInt8, order_ref UInt64, account UInt32,"
          + " security LowCardinality(String), side FixedString(1), qty UInt32, remaining UInt32, limit_px Float64,"
          + " status LowCardinality(String), last_px Float64, last_qty UInt32, created DateTime64(3), cseq UInt64,"
          + " ordinal UInt16, client_key UInt64)" + set, new byte[0]);
    }

    public long resume(String feed, int part) throws Exception {
      return durable = Long.parseLong(q("SELECT max(seq) FROM " + feed + " WHERE part=" + part, null));
    }

    public long write(String feed, int part, Batch b) throws Exception {
      StringBuilder sb = new StringBuilder(b.n * 120);
      for (int r = 0; r < b.n; r++) {
        sb.append(b.ts[r]).append('\t')
          .append(b.seq[r]).append('\t').append(part).append('\t');
        if (b.trades) {
          sb.append(b.key[r]).append('\t').append((char) b.side[r]).append('\t').append(b.acct[r]).append('\t')
            .append(b.sec[r]).append('\t').append(b.qty[r]).append('\t').append(b.px[r]).append('\t')
            .append(b.ref[r]).append('\t').append(b.cseq[r]).append('\n');
        } else {
          sb.append(b.key[r]).append('\t').append(b.acct[r]).append('\t').append(b.sec[r]).append('\t')
            .append((char) b.side[r]).append('\t').append(b.qty[r]).append('\t').append(b.remaining[r]).append('\t')
            .append(b.px[r]).append('\t').append(b.status[r]).append('\t').append(b.lastPx[r]).append('\t')
            .append(b.lastQty[r]).append('\t').append(b.created[r]).append('\t').append(b.cseq[r]).append('\t')
            .append(b.ordinal[r]).append('\t').append(b.ref[r]).append('\n');
        }
      }
      q(b.trades ? INS_TRADES : INS_ORDERS, sb.toString().getBytes(StandardCharsets.UTF_8));
      return durable = b.seq[b.n - 1];
    }

    public long durable(String feed, int part) { return durable; }

    // Millisecond epochs on the wire, converted server-side (TSV has no portable DateTime64 integer form).
    static final String INS_TRADES = "INSERT INTO trades SELECT fromUnixTimestamp64Milli(ts), seq, part, trade_seq, side,"
        + " account, security, qty, px, order_ref, cseq FROM input('ts Int64, seq UInt64, part UInt8, trade_seq UInt64,"
        + " side FixedString(1), account UInt32, security String, qty UInt32, px Float64, order_ref UInt64, cseq UInt64')"
        + " FORMAT TabSeparated";
    static final String INS_ORDERS = "INSERT INTO orders SELECT fromUnixTimestamp64Milli(ts), seq, part, order_ref, account,"
        + " security, side, qty, remaining, limit_px, status, last_px, last_qty, fromUnixTimestamp64Milli(created), cseq,"
        + " ordinal, client_key FROM input('ts Int64, seq UInt64, part UInt8, order_ref UInt64, account UInt32,"
        + " security String, side FixedString(1), qty UInt32, remaining UInt32, limit_px Float64, status String,"
        + " last_px Float64, last_qty UInt32, created Int64, cseq UInt64, ordinal UInt16, client_key UInt64')"
        + " FORMAT TabSeparated";
  }

  // ---------------------------------------------------------------- QuestDB
  static final class QuestDb implements Sink {
    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final String base; long durable;
    QuestDb(String store) { base = "http://" + store; }

    String exec(String sql) throws Exception {
      HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/exec?query="
          + URLEncoder.encode(sql, StandardCharsets.UTF_8))).timeout(Duration.ofMinutes(10)).build(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() != 200) throw new IllegalStateException("questdb " + r.statusCode() + ": " + r.body());
      return r.body();
    }

    public void prepare() throws Exception {
      exec("CREATE TABLE IF NOT EXISTS trades (ts TIMESTAMP, seq LONG, part INT, trade_seq LONG, side SYMBOL,"
          + " account INT, security SYMBOL CAPACITY 1024, qty INT, px DOUBLE, order_ref LONG, cseq LONG)"
          + " TIMESTAMP(ts) PARTITION BY DAY WAL");
      exec("CREATE TABLE IF NOT EXISTS orders (ts TIMESTAMP, seq LONG, part INT, order_ref LONG, account INT,"
          + " security SYMBOL CAPACITY 1024, side SYMBOL, qty INT, remaining INT, limit_px DOUBLE, status SYMBOL,"
          + " last_px DOUBLE, last_qty INT, created TIMESTAMP, cseq LONG, ordinal INT, client_key LONG)"
          + " TIMESTAMP(ts) PARTITION BY DAY WAL");
    }

    public long resume(String feed, int part) throws Exception {
      // WAL tables apply asynchronously: read max(seq) only once every committed WAL txn is applied.
      for (int i = 0; ; i++) {
        String w = exec("SELECT writerTxn, sequencerTxn FROM wal_tables() WHERE name='" + feed + "'");
        String row = w.substring(w.indexOf("\"dataset\":[[") + 12, w.indexOf("]]"));
        String[] v = row.split(",");
        if (v[0].equals(v[1])) break;
        if (i > 600) throw new IllegalStateException("WAL not applied for " + feed + ": " + row);
        Thread.sleep(1000);
      }
      String r = exec("SELECT max(seq) FROM " + feed + " WHERE part=" + part);
      String v = r.substring(r.indexOf("\"dataset\":[[") + 12, r.indexOf("]]"));
      return durable = v.equals("null") ? 0 : Long.parseLong(v);
    }

    public long write(String feed, int part, Batch b) throws Exception {
      StringBuilder sb = new StringBuilder(b.n * 160);
      for (int r = 0; r < b.n; r++) {
        sb.append(feed).append(",side=").append((char) b.side[r]).append(",security=").append(b.sec[r]);
        if (!b.trades) sb.append(",status=").append(b.status[r]);
        sb.append(" seq=").append(b.seq[r]).append("i,part=").append(part).append("i,account=").append(b.acct[r])
          .append("i,qty=").append(b.qty[r]).append("i,cseq=").append(b.cseq[r]).append('i');
        if (b.trades) {
          sb.append(",trade_seq=").append(b.key[r]).append("i,px=").append(b.px[r]).append(",order_ref=").append(b.ref[r]).append('i');
        } else {
          sb.append(",order_ref=").append(b.key[r]).append("i,remaining=").append(b.remaining[r]).append("i,limit_px=")
            .append(b.px[r]).append(",last_px=").append(b.lastPx[r]).append(",last_qty=").append(b.lastQty[r])
            .append("i,created=").append(b.created[r] * 1000).append("t,ordinal=").append(b.ordinal[r])
            .append("i,client_key=").append(b.ref[r]).append('i');
        }
        sb.append(' ').append(b.ts[r] * 1_000_000L).append('\n');
      }
      HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/write"))
          .timeout(Duration.ofMinutes(5))
          .POST(HttpRequest.BodyPublishers.ofByteArray(sb.toString().getBytes(StandardCharsets.UTF_8))).build(),
          HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() != 204) throw new IllegalStateException("questdb ilp " + r.statusCode() + ": " + r.body());
      return durable = b.seq[b.n - 1];
    }

    public long durable(String feed, int part) { return durable; }
  }

  // ---------------------------------------------------------------- KDB-X
  static final class Kdb implements Sink {
    final com.kx.c c;
    Kdb(String store) throws Exception { String[] hp = store.split(":"); c = new com.kx.c(hp[0], Integer.parseInt(hp[1])); }

    public long resume(String feed, int part) throws Exception {
      return ((Number) c.k("res", feed, (short) part)).longValue();
    }

    public long write(String feed, int part, Batch b) throws Exception {
      int n = b.n;
      long[] ts = new long[n];
      // q timestamps are ns since 2000.01.01
      for (int r = 0; r < n; r++) ts[r] = (b.ts[r] - 946_684_800_000L) * 1_000_000L;
      char[] side = new char[n]; for (int r = 0; r < n; r++) side[r] = (char) b.side[r];
      Object[] cols; String[] names;
      if (b.trades) {
        names = new String[]{"ts", "seq", "tradeSeq", "side", "account", "security", "qty", "px", "orderRef", "cseq"};
        cols = new Object[]{ts, cut(b.seq, n), cut(b.key, n), side, cut(b.acct, n), Arrays.copyOf(b.sec, n), cut(b.qty, n),
            Arrays.copyOf(b.px, n), cut(b.ref, n), cut(b.cseq, n)};
      } else {
        long[] cr = new long[n]; for (int r = 0; r < n; r++) cr[r] = (b.created[r] - 946_684_800_000L) * 1_000_000L;
        names = new String[]{"ts", "seq", "orderRef", "account", "security", "side", "qty", "remaining", "limitPx",
            "status", "lastPx", "lastQty", "created", "cseq", "ordinal", "clientKey"};
        cols = new Object[]{ts, cut(b.seq, n), cut(b.key, n), cut(b.acct, n), Arrays.copyOf(b.sec, n), side, cut(b.qty, n),
            cut(b.remaining, n), Arrays.copyOf(b.px, n), Arrays.copyOf(b.status, n), Arrays.copyOf(b.lastPx, n),
            cut(b.lastQty, n), cr, cut(b.cseq, n), cut(b.ordinal, n), cut(b.ref, n)};
      }
      return ((Number) c.k("upd", feed, (short) part, new com.kx.c.Flip(new com.kx.c.Dict(names, cols)))).longValue();
    }

    public long durable(String feed, int part) throws Exception {
      return ((Number) c.k("dur", feed, (short) part)).longValue();
    }

    static long[] cut(long[] a, int n) { return Arrays.copyOf(a, n); }
    static int[] cut(int[] a, int n) { return Arrays.copyOf(a, n); }
  }
}
