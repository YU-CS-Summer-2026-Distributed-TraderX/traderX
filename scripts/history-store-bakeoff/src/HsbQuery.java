import io.nats.client.*;
import io.nats.client.api.*;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Exactly-once check and query latencies for one store, after HistoryFeed has drained.
 *
 * <p>mode=check: per table, rows / distinct (partition, seq) / distinct business key (trade: tradeSeq*2+side, order:
 * orderRef*4+ordinal) in the store against the stream's own per-subject message counts.
 * mode=query: (a) today's fills for one account, page 11 of 100; (b) one account's VWAP vs the market's per
 * security over the last 5 minutes (TCA), and (b2) VWAP by account and security over the same window;
 * (d) one account's position and average buy price as of the run's midpoint; (c) the full end-of-day export
 * of both tables as CSV streamed to this client (bytes counted, not stored). Each of a/b/b2/d runs once cold
 * (the caller drops the store host's page cache first) then {@code reps} warm; prints min/median/max.
 *
 * <p>Args: sink, store (host:port), url (NATS), stream, mode (check|query|export), account, reps.
 */
public final class HsbQuery {
  static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  public static void main(String[] a) throws Exception {
    Map<String, String> o = new HashMap<>();
    for (String s : a) { int i = s.indexOf('='); o.put(s.substring(0, i), s.substring(i + 1)); }
    String sink = o.get("sink"), store = o.get("store"), mode = o.getOrDefault("mode", "query");
    int acct = Integer.parseInt(o.getOrDefault("account", "4242")), reps = Integer.parseInt(o.getOrDefault("reps", "5"));
    Store s = switch (sink) { case "clickhouse" -> new Ch(store); case "questdb" -> new Qdb(store); case "kdb" -> new Kdb(store);
      default -> throw new IllegalArgumentException(sink); };
    switch (mode) {
      case "check" -> check(s, o.getOrDefault("url", "nats://127.0.0.1:4222"), o.getOrDefault("stream", "TRADERX_FEED"));
      case "export" -> {
        for (String t : new String[]{"trades", "orders"}) {
          long t0 = System.nanoTime();
          long[] rb = s.export(t);
          double sec = (System.nanoTime() - t0) / 1e9;
          System.out.printf("EXPORT %s rows=%d bytes=%d seconds=%.1f rows/s=%.0f MB/s=%.0f%n", t, rb[0], rb[1], sec, rb[0] / sec, rb[1] / sec / 1e6);
        }
      }
      default -> {
        long[] mm = s.bounds();                     // ms epoch of min/max trade ts
        long t2 = mm[1], t1 = t2 - 300_000, mid = (mm[0] + mm[1]) / 2;
        System.out.printf("bounds %s .. %s, window %s .. %s, as-of %s%n", Instant.ofEpochMilli(mm[0]), Instant.ofEpochMilli(mm[1]),
            Instant.ofEpochMilli(t1), Instant.ofEpochMilli(t2), Instant.ofEpochMilli(mid));
        String[][] qs = s.queries(acct, mm[0], t1, t2, mid);
        for (String[] q : qs) {
          double[] ms = new double[reps + 1]; String rows = "";
          for (int r = 0; r <= reps; r++) {
            long t0 = System.nanoTime();
            rows = s.run(q[1]);
            ms[r] = (System.nanoTime() - t0) / 1e6;
          }
          double cold = ms[0]; double[] w = Arrays.copyOfRange(ms, 1, ms.length); Arrays.sort(w);
          System.out.printf("QUERY %s cold=%.1fms warm min=%.1f median=%.1f max=%.1f ms result=%s%n", q[0], cold, w[0], w[w.length / 2], w[w.length - 1], rows);
        }
      }
    }
  }

  static void check(Store s, String url, String stream) throws Exception {
    long streamTrades = 0, streamOrders = 0;
    try (Connection nc = Nats.connect(url)) {
      JetStreamManagement jsm = nc.jetStreamManagement();
      for (String name : jsm.getStreamNames()) {
        if (!name.startsWith(stream)) continue;
        StreamInfo si = jsm.getStreamInfo(name, StreamInfoOptions.allSubjects());
        for (Subject sub : si.getStreamState().getSubjects()) {
          if (sub.getName().startsWith("/trades")) streamTrades += sub.getCount();
          else if (sub.getName().startsWith("/orders")) streamOrders += sub.getCount();
        }
        System.out.printf("STREAM %s msgs=%d bytes=%d first=%d last=%d%n", name, si.getStreamState().getMsgCount(),
            si.getStreamState().getByteCount(), si.getStreamState().getFirstSequence(), si.getStreamState().getLastSequence());
      }
      System.out.printf("STREAM total trades=%d orders=%d%n", streamTrades, streamOrders);
    }
    for (String t : new String[]{"trades", "orders"}) {
      long[] c = s.counts(t);
      long expect = t.equals("trades") ? streamTrades : streamOrders;
      // expectTrades/expectOrders: the fill's acked totals, once the stream has aged or been refilled under the store
      String fixed = System.getProperty(t.equals("trades") ? "expectTrades" : "expectOrders");
      if (fixed != null) expect = Long.parseLong(fixed);
      boolean ok = c[0] == expect && c[1] == expect && c[2] == expect;
      System.out.printf("CHECK %s rows=%d distinctSeq=%d distinctKey=%d stream=%d -> %s%n", t, c[0], c[1], c[2], expect,
          ok ? "EXACTLY-ONCE" : "MISMATCH");
    }
  }

  interface Store {
    long[] bounds() throws Exception;
    long[] counts(String t) throws Exception;
    String[][] queries(int acct, long day, long t1, long t2, long asOf);
    /** Runs a query, returns "<rows> rows". */
    String run(String q) throws Exception;
    long[] export(String t) throws Exception;
  }

  static String httpGet(String url) throws Exception {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30)).build(),
        HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 200) throw new IllegalStateException(r.statusCode() + ": " + r.body());
    return r.body();
  }

  /** Streams the body, counting rows (newlines) and bytes. */
  static long[] httpCount(String url) throws Exception {
    HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofHours(2)).build(),
        HttpResponse.BodyHandlers.ofInputStream());
    if (r.statusCode() != 200) throw new IllegalStateException("export " + r.statusCode());
    byte[] buf = new byte[1 << 20]; long rows = 0, bytes = 0; int n;
    try (InputStream in = r.body()) {
      while ((n = in.read(buf)) > 0) { bytes += n; for (int i = 0; i < n; i++) if (buf[i] == '\n') rows++; }
    }
    return new long[]{rows, bytes};
  }

  static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

  static final class Ch implements Store {
    final String base;
    Ch(String store) { base = "http://" + store + "/?query="; }
    String one(String sql) throws Exception { return httpGet(base + enc(sql)).trim(); }
    public long[] bounds() throws Exception {
      String[] v = one("SELECT toUnixTimestamp64Milli(min(ts)), toUnixTimestamp64Milli(max(ts)) FROM trades").split("\t");
      return new long[]{Long.parseLong(v[0]), Long.parseLong(v[1])};
    }
    public long[] counts(String t) throws Exception {
      String key = t.equals("trades") ? "trade_seq*2 + (side='S')" : "order_ref*4 + ordinal";
      String[] v = one("SELECT count(), uniqExact(part, seq), uniqExact(" + key + ") FROM " + t).split("\t");
      return new long[]{Long.parseLong(v[0]), Long.parseLong(v[1]), Long.parseLong(v[2])};
    }
    static String ts(long ms) { return "fromUnixTimestamp64Milli(" + ms + ")"; }
    public String[][] queries(int a, long day, long t1, long t2, long asOf) {
      return new String[][]{
          {"a_fills_page", "SELECT ts, trade_seq, side, security, qty, px, order_ref FROM trades WHERE account=" + a
              + " AND ts >= toStartOfDay(" + ts(t2) + ") ORDER BY ts DESC, seq DESC LIMIT 100 OFFSET 1000"},
          {"b_tca_vwap_account", "SELECT security, sumIf(px*qty, account=" + a + ")/sumIf(qty, account=" + a + ") AS acct_vwap,"
              + " sum(px*qty)/sum(qty) AS mkt_vwap, sumIf(qty, account=" + a + ") AS acct_qty, count() AS n FROM trades"
              + " WHERE ts BETWEEN " + ts(t1) + " AND " + ts(t2) + " GROUP BY security HAVING acct_qty > 0 ORDER BY security"},
          {"b2_vwap_by_account", "SELECT count() FROM (SELECT account, security, sum(px*qty)/sum(qty) AS vwap, sum(qty) AS tot_qty FROM trades"
              + " WHERE ts BETWEEN " + ts(t1) + " AND " + ts(t2) + " GROUP BY account, security)"},
          {"d_asof_position", "SELECT security, sum(if(side='B', toInt64(qty), -toInt64(qty))) AS pos,"
              + " sumIf(px*qty, side='B')/sumIf(qty, side='B') AS avg_buy_px FROM trades WHERE account=" + a
              + " AND ts <= " + ts(asOf) + " GROUP BY security ORDER BY security"}};
    }
    public String run(String q) throws Exception {
      String body = httpGet(base + enc(q));
      return body.isEmpty() ? "0 rows" : body.split("\n").length + " rows";
    }
    public long[] export(String t) throws Exception { return httpCount(base + enc("SELECT * FROM " + t + " FORMAT CSV")); }
  }

  static final class Qdb implements Store {
    final String base;
    Qdb(String store) { base = "http://" + store; }
    String dataset(String sql) throws Exception {
      String r = httpGet(base + "/exec?query=" + enc(sql));
      return r.substring(r.indexOf("\"dataset\":[") + 11, r.lastIndexOf("]"));
    }
    long[] row(String sql) throws Exception {
      String d = dataset(sql); d = d.substring(d.indexOf('[') + 1, d.indexOf(']'));
      return Arrays.stream(d.split(",")).mapToLong(Long::parseLong).toArray();
    }
    public long[] bounds() throws Exception {
      long[] v = row("SELECT cast(min(ts) AS long), cast(max(ts) AS long) FROM trades");
      return new long[]{v[0] / 1000, v[1] / 1000};
    }
    public long[] counts(String t) throws Exception {
      String key = t.equals("trades") ? "trade_seq*2 + CASE WHEN side='S' THEN 1 ELSE 0 END" : "order_ref*4 + ordinal";
      // Per partition (keys are disjoint across partitions): count_distinct over a whole 240M-row table asks QuestDB
      // for a single 4 GiB allocation and is refused ("too large allocation requested").
      long[] sum = new long[3];
      for (int p = 0; p < 4; p++) {
        long[] c = row("SELECT count(), count_distinct(seq) FROM " + t + " WHERE part=" + p);
        long[] k = row("SELECT count_distinct(" + key + ") FROM " + t + " WHERE part=" + p);
        sum[0] += c[0]; sum[1] += c[1]; sum[2] += k[0];
      }
      return sum;
    }
    static String ts(long ms) { return "'" + Instant.ofEpochMilli(ms) + "'"; }
    public String[][] queries(int a, long day, long t1, long t2, long asOf) {
      String dayStart = "'" + Instant.ofEpochMilli(t2).toString().substring(0, 10) + "T00:00:00.000Z'";
      return new String[][]{
          {"a_fills_page", "SELECT ts, trade_seq, side, security, qty, px, order_ref FROM trades WHERE account=" + a
              + " AND ts >= " + dayStart + " ORDER BY ts DESC, seq DESC LIMIT 1000,1100"},
          {"b_tca_vwap_account", "SELECT * FROM (SELECT security, sum(CASE WHEN account=" + a + " THEN px*qty ELSE 0 END)"
              + "/sum(CASE WHEN account=" + a + " THEN qty ELSE 0 END) acct_vwap, sum(px*qty)/sum(qty) mkt_vwap,"
              + " sum(CASE WHEN account=" + a + " THEN qty ELSE 0 END) acct_qty, count() n FROM trades WHERE ts BETWEEN "
              + ts(t1) + " AND " + ts(t2) + ") WHERE acct_qty > 0 ORDER BY security"},
          {"b2_vwap_by_account", "SELECT count() FROM (SELECT account, security, sum(px*qty)/sum(qty) vwap, sum(qty) tot_qty FROM trades"
              + " WHERE ts BETWEEN " + ts(t1) + " AND " + ts(t2) + ")"},
          {"d_asof_position", "SELECT security, sum(CASE WHEN side='B' THEN qty ELSE -qty END) pos,"
              + " sum(CASE WHEN side='B' THEN px*qty ELSE 0 END)/sum(CASE WHEN side='B' THEN qty ELSE 0 END) avg_buy_px"
              + " FROM trades WHERE account=" + a + " AND ts <= " + ts(asOf) + " ORDER BY security"}};
    }
    public String run(String q) throws Exception {
      String r = httpGet(base + "/exec?query=" + enc(q));
      int c = r.indexOf("\"count\":");
      return (c < 0 ? "?" : r.substring(c + 8).replaceAll("[^0-9].*", "")) + " rows";
    }
    public long[] export(String t) throws Exception { return httpCount(base + "/exp?query=" + enc("SELECT * FROM " + t)); }
  }

  /** Queries go to the HDB process (q DB), never to the feed's write-down process. */
  static final class Kdb implements Store {
    final com.kx.c c;
    Kdb(String store) throws Exception { String[] hp = store.split(":"); c = new com.kx.c(hp[0], Integer.parseInt(hp[1])); }
    Object k(String q) throws Exception { return c.k(q); }
    static String ts(long ms) { return "(2000.01.01D+" + (ms - 946_684_800_000L) * 1_000_000L + ")"; }
    public long[] bounds() throws Exception {
      long[] v = (long[]) k("946684800000+(`long$raze value flip select min ts, max ts from trades where date=last date) div 1000000");
      return v;
    }
    public long[] counts(String t) throws Exception {
      String key = t.equals("trades") ? "(2*tradeSeq)+side=\"S\"" : "(4*orderRef)+ordinal";
      // One partition per call, garbage-collected between (keys are disjoint across partitions): materialising all
      // four partitions' vectors and distinct sets in one expression passes the 16 GiB licence heap ('stop).
      long[] sum = new long[3];
      for (int p = 0; p < 4; p++) {
        long[] c = (long[]) k("{r:(count x;count distinct x`seq;count distinct x`k); .Q.gc[]; r} select seq, k:" + key
            + " from " + t + " where date=last date, part=" + p + "h");
        for (int j = 0; j < 3; j++) sum[j] += c[j];
      }
      return sum;
    }
    public String[][] queries(int a, long day, long t1, long t2, long asOf) {
      String w = " where date=last date, ts within (" + ts(t1) + ";" + ts(t2) + ")";
      return new String[][]{
          {"a_fills_page", "100 sublist 1000_`ts`seq xdesc select ts,seq,tradeSeq,side,security,qty,px,orderRef from trades where date=last date, account=" + a + "i"},
          {"b_tca_vwap_account", "select from (select acctVwap:(sum px*qty*m)%sum qty*m, mktVwap:qty wavg px, acctQty:sum qty*m, n:count i"
              + " by security from update m:account=" + a + "i from select account,security,qty,px from trades" + w + ") where acctQty>0"},
          {"b2_vwap_by_account", "count select vwap:qty wavg px, totQty:sum qty by account, security from trades" + w},
          {"d_asof_position", "select pos:sum ?[side=\"B\";qty;neg qty], avgBuyPx:(sum px*qty*b)%sum qty*b by security from"
              + " update b:side=\"B\" from select side,qty,px,security from trades where date=last date, account=" + a + "i, ts<=" + ts(asOf)}};
    }
    public String run(String q) throws Exception {
      Object r = k("{$[99h=type x;count key x;98h=type x;count x;x]} " + q);
      return r + " rows";
    }
    /** Chunked: the server formats CSV for 2M rows at a time (a whole-table 0: would need the table in heap). */
    public long[] export(String t) throws Exception {
      long n = ((Number) k("count select from " + t + " where date=last date")).longValue();
      long rows = 0, bytes = 0;
      for (long lo = 0; lo < n; lo += 2_000_000) {
        Object[] lines = (Object[]) k("{r:\",\" 0: select from " + t + " where date=last date, i within x; .Q.gc[]; 1_r} "
            + lo + " " + (Math.min(n, lo + 2_000_000) - 1));
        for (Object l : lines) { bytes += ((char[]) l).length + 1; rows++; }
      }
      return new long[]{rows, bytes};
    }
  }
}
