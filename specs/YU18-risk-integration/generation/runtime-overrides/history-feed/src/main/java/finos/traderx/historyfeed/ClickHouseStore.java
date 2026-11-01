package finos.traderx.historyfeed;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The history store (FR-HST-04): ClickHouse over HTTP. One INSERT per batch into MergeTree tables
 * {@code PARTITION BY toDate(ts) ORDER BY (account, ts)} with fsync after every insert, so an INSERT that returned
 * is durable and the feed may ack behind it. One insert is atomic for a single-day batch (a part per partition).
 * Queries take every caller value as a ClickHouse query parameter ({@code {name:Type}}), never spliced into SQL.
 */
final class ClickHouseStore {
  private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(10)).build();
  private final String base;

  ClickHouseStore(String url) { this.base = url.endsWith("/") ? url : url + "/"; }

  static final String SETTINGS = " ENGINE=MergeTree PARTITION BY toDate(ts) ORDER BY (account, ts)"
      + " SETTINGS fsync_after_insert=1, fsync_part_directory=1";

  void createTables() throws Exception {
    exec("CREATE TABLE IF NOT EXISTS trades (ts DateTime64(3), seq UInt64, part UInt16, trade_seq UInt64,"
        + " side FixedString(1), account UInt32, security LowCardinality(String), qty UInt32, px Float64,"
        + " order_ref UInt64, cseq UInt64)" + SETTINGS);
    exec("CREATE TABLE IF NOT EXISTS orders (ts DateTime64(3), seq UInt64, part UInt16, order_ref UInt64,"
        + " account UInt32, security LowCardinality(String), side FixedString(1), qty UInt32, remaining UInt32,"
        + " limit_px Float64, status LowCardinality(String), last_px Float64, last_qty UInt32, created DateTime64(3),"
        + " cseq UInt64, ordinal UInt16, client_key UInt64)" + SETTINGS);
    // FR-HST-07: which incarnation of which stream each lane's rows came from (a cursor is only a number).
    exec("CREATE TABLE IF NOT EXISTS history_cursor (stream String, tbl String, part UInt16, incarnation String,"
        + " bound DateTime64(3) DEFAULT now64(3)) ENGINE=MergeTree ORDER BY (stream, tbl, part, bound)"
        + " SETTINGS fsync_after_insert=1, fsync_part_directory=1");
  }

  /**
   * The incarnation this lane's rows are bound to, or null when none was ever bound. The latest binding wins; a bind
   * in the same millisecond as the attestation it uses up wins the tie.
   */
  String incarnation(String stream, String table, int part) throws Exception {
    String v = query("SELECT incarnation FROM history_cursor WHERE stream = {s:String} AND tbl = {t:String}"
        + " AND part = {p:UInt16} ORDER BY bound DESC, startsWith(incarnation, '" + FeedLane.ATTESTED + "') LIMIT 1", Map.of("s", stream, "t", table, "p", "" + part)).trim();
    return v.isEmpty() ? null : v;
  }

  /** Durably binds the lane to an incarnation, before the lane's first row from it. */
  void bind(String stream, String table, int part, String incarnation) throws Exception {
    String row = stream + "\t" + table + "\t" + part + "\t" + incarnation + "\n";
    post("INSERT INTO history_cursor (stream, tbl, part, incarnation) FORMAT TabSeparated", Map.of(),
        row.getBytes(StandardCharsets.UTF_8));
  }

  /** The store's cursor for one (table, partition): the highest stream sequence it holds, 0 when empty. */
  long maxSeq(String table, int part) throws Exception {
    return Long.parseLong(query("SELECT max(seq) FROM " + table + " WHERE part = {p:UInt16}", Map.of("p", "" + part)).trim());
  }

  void insert(Rows b, int part) throws Exception {
    StringBuilder sb = new StringBuilder(b.n * 128);
    for (int r = 0; r < b.n; r++) {
      sb.append(b.ts[r]).append('\t').append(b.seq[r]).append('\t').append(part).append('\t');
      if (b.trades) {
        sb.append(b.key[r]).append('\t').append((char) b.side[r]).append('\t').append(b.acct[r]).append('\t')
          .append(b.sec[r]).append('\t').append(b.qty[r]).append('\t').append(b.px[r]).append('\t')
          .append(b.ref[r]).append('\t').append(b.cseq[r]).append('\n');
      } else {
        sb.append(b.key[r]).append('\t').append(b.acct[r]).append('\t').append(b.sec[r]).append('\t')
          .append((char) b.side[r]).append('\t').append(b.qty[r]).append('\t').append(b.remaining[r]).append('\t')
          .append(b.px[r]).append('\t').append(b.status[r]).append('\t').append(b.lastPx[r]).append('\t')
          .append(b.lastQty[r]).append('\t').append(b.created[r]).append('\t').append(b.cseq[r]).append('\t')
          .append(b.ordinal[r]).append('\t').append(Long.toUnsignedString(b.ref[r])).append('\n');
      }
    }
    post(b.trades ? INSERT_TRADES : INSERT_ORDERS, Map.of(), sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  // Millisecond epochs on the wire, converted server-side: TSV has no portable integer form of DateTime64.
  static final String INSERT_TRADES = "INSERT INTO trades SELECT fromUnixTimestamp64Milli(ts), seq, part, trade_seq,"
      + " side, account, security, qty, px, order_ref, cseq FROM input('ts Int64, seq UInt64, part UInt16,"
      + " trade_seq UInt64, side FixedString(1), account UInt32, security String, qty UInt32, px Float64,"
      + " order_ref UInt64, cseq UInt64') FORMAT TabSeparated";
  static final String INSERT_ORDERS = "INSERT INTO orders SELECT fromUnixTimestamp64Milli(ts), seq, part, order_ref,"
      + " account, security, side, qty, remaining, limit_px, status, last_px, last_qty,"
      + " fromUnixTimestamp64Milli(created), cseq, ordinal, client_key FROM input('ts Int64, seq UInt64, part UInt16,"
      + " order_ref UInt64, account UInt32, security String, side FixedString(1), qty UInt32, remaining UInt32,"
      + " limit_px Float64, status String, last_px Float64, last_qty UInt32, created Int64, cseq UInt64,"
      + " ordinal UInt16, client_key UInt64') FORMAT TabSeparated";

  // FR-HST-05: the four access paths the bake-off measured. Times are ClickHouse DateTime64 text or epoch ms.
  static final String FILLS = "SELECT ts, trade_seq, side, security, qty, px, order_ref FROM trades"
      + " WHERE account = {account:UInt32} AND toDate(ts) = {day:Date}"
      + " ORDER BY ts DESC, seq DESC LIMIT {limit:UInt32} OFFSET {offset:UInt64} FORMAT JSON";
  static final String TCA = "SELECT security, sumIf(px * qty, account = {account:UInt32}) / sumIf(qty, account = {account:UInt32})"
      + " AS account_vwap, sum(px * qty) / sum(qty) AS market_vwap, sumIf(qty, account = {account:UInt32}) AS account_qty,"
      + " count() AS market_fills FROM trades WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)}"
      + " GROUP BY security HAVING account_qty > 0 ORDER BY security FORMAT JSON";
  static final String VWAP = "SELECT account, security, sum(px * qty) / sum(qty) AS vwap, sum(qty) AS total_qty FROM trades"
      + " WHERE ts >= {from:DateTime64(3)} AND ts < {to:DateTime64(3)} GROUP BY account, security"
      + " ORDER BY account, security FORMAT JSON";
  static final String POSITIONS = "SELECT security, sum(if(side = 'B', toInt64(qty), -toInt64(qty))) AS position,"
      + " sumIf(px * qty, side = 'B') / sumIf(qty, side = 'B') AS avg_buy_px FROM trades"
      + " WHERE account = {account:UInt32} AND ts <= {asOf:DateTime64(3)} GROUP BY security ORDER BY security FORMAT JSON";
  static final String EXPORT_TRADES = "SELECT * FROM trades WHERE toDate(ts) = {day:Date} ORDER BY ts, part, seq FORMAT CSVWithNames";
  static final String EXPORT_ORDERS = "SELECT * FROM orders WHERE toDate(ts) = {day:Date} ORDER BY ts, part, seq FORMAT CSVWithNames";

  String exec(String sql) throws Exception { return new String(post(sql, Map.of(), new byte[0]).readAllBytes(), StandardCharsets.UTF_8); }

  String query(String sql, Map<String, String> params) throws Exception {
    try (InputStream in = stream(sql, params)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
  }

  /** The response body streamed (the export is the whole day). */
  InputStream stream(String sql, Map<String, String> params) throws Exception {
    return post(sql, params, null);
  }

  private InputStream post(String sql, Map<String, String> params, byte[] body) throws Exception {
    StringJoiner q = new StringJoiner("&", base + "?", "");
    q.add("query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8));
    params.forEach((k, v) -> q.add("param_" + k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
    HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(q.toString())).timeout(Duration.ofMinutes(30));
    r = body == null ? r.GET() : r.POST(HttpRequest.BodyPublishers.ofByteArray(body));
    HttpResponse<InputStream> resp = http.send(r.build(), HttpResponse.BodyHandlers.ofInputStream());
    if (resp.statusCode() != 200) {
      String err;
      try (InputStream in = resp.body()) { err = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
      throw new StoreException(resp.statusCode(), err.trim());
    }
    return resp.body();
  }

  static final class StoreException extends Exception {
    final int status;
    StoreException(int status, String message) { super("clickhouse " + status + ": " + message); this.status = status; }
  }
}
