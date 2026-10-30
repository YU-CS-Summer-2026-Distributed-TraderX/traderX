package finos.traderx.historyfeed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * History feed (FR-HST-01..06): every trade side and order event of TRADERX_FEED into ClickHouse, and the history
 * queries over HTTP. A reader of the capture stream only: trade-processor creates and sizes the streams.
 *
 * <p>Environment (the trade-processor names where they mean the same thing): NATS_ADDRESS, PROJECTION_FEED_STREAM
 * (TRADERX_FEED), PROJECTION_FEED_PARTITIONS (1), PROJECTION_FEED_STREAM_LAYOUT (one | partition: TRADERX_FEED_P&lt;p&gt;),
 * CLICKHOUSE_URL (http://clickhouse:8123), HISTORY_BATCH_ROWS (100000), HISTORY_FLUSH_MS (500), HISTORY_FETCH (5000),
 * HISTORY_HTTP_PORT (18095).
 *
 * <p>HTTP: GET /health; GET /history/accounts/{id}/fills?day=&amp;limit=&amp;offset=; /history/accounts/{id}/tca?from=&amp;to=;
 * /history/accounts/{id}/positions?asOf=; /history/vwap?from=&amp;to=; /history/export/{trades|orders}?day= (CSV).
 * Times are ISO-8601 instants or epoch milliseconds, days ISO dates, all UTC.
 */
public final class HistoryFeedMain {
  static final DateTimeFormatter CH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

  public static void main(String[] args) throws Exception {
    String natsUrl = env("NATS_ADDRESS", "nats://localhost:4222");
    String stream = env("PROJECTION_FEED_STREAM", "TRADERX_FEED");
    int partitions = Integer.parseInt(env("PROJECTION_FEED_PARTITIONS", "1"));
    String layout = env("PROJECTION_FEED_STREAM_LAYOUT", "one");
    int batch = Integer.parseInt(env("HISTORY_BATCH_ROWS", "100000"));
    int flushMs = Integer.parseInt(env("HISTORY_FLUSH_MS", "500"));
    int fetch = Integer.parseInt(env("HISTORY_FETCH", "5000"));
    int port = Integer.parseInt(env("HISTORY_HTTP_PORT", "18095"));

    ClickHouseStore store = new ClickHouseStore(env("CLICKHOUSE_URL", "http://clickhouse:8123"));
    for (int attempt = 1; ; attempt++) {     // the store may start after us
      try { store.createTables(); break; }
      catch (Exception e) { if (attempt == 60) throw e; System.err.println("clickhouse not ready: " + e); Thread.sleep(2000); }
    }
    List<FeedLane> lanes = new ArrayList<>();
    for (String feed : new String[]{"trades", "orders"}) {
      for (int p = 0; p < partitions; p++) {
        // A connection per lane: one connection's reader thread is a ceiling near 1M msgs/s of JSON.
        Connection nc = Nats.connect(new Options.Builder().server(natsUrl).bufferSize(1 << 20).maxReconnects(-1).build());
        FeedLane lane = new FeedLane(nc, store, streamFor(stream, layout, p, partitions), feed, p, batch, flushMs, fetch);
        lanes.add(lane);
        Thread t = new Thread(lane, lane.durable);
        t.setDaemon(true);
        t.start();
      }
    }
    serve(port, store, lanes);
    System.out.printf("history-feed: %d lanes on %s (%s, P=%d) -> %s; http :%d%n", lanes.size(), stream, layout,
        partitions, env("CLICKHOUSE_URL", "http://clickhouse:8123"), port);
  }

  /** Same naming as trade-processor's JetStreamFeed.streamFor (FR-DRS-32). */
  static String streamFor(String base, String layout, int partition, int partitionCount) {
    return "partition".equalsIgnoreCase(layout) && partitionCount > 1 ? base + "_P" + partition : base;
  }

  static String env(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }

  static void serve(int port, ClickHouseStore store, List<FeedLane> lanes) throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress(port), 64);
    s.setExecutor(Executors.newFixedThreadPool(8));
    s.createContext("/health", x -> {
      StringBuilder sb = new StringBuilder("{\"ready\":").append(lanes.stream().allMatch(l -> l.ready)).append(",\"lanes\":[");
      for (int i = 0; i < lanes.size(); i++) {
        FeedLane l = lanes.get(i);
        if (i > 0) sb.append(',');
        sb.append("{\"durable\":\"").append(l.durable).append("\",\"stream\":\"").append(l.stream)
          .append("\",\"cursor\":").append(l.cursor.get()).append(",\"rows\":").append(l.rows.get())
          .append(",\"pending\":").append(l.pending()).append(",\"recreates\":").append(l.recreates.get())
          .append(",\"writeFailures\":").append(l.writeFailures.get()).append(",\"lastWriteMs\":").append(l.lastWriteMs.get())
          .append(",\"lastError\":\"").append(l.lastError.replace("\\", "/").replace("\"", "'")).append("\"}");
      }
      reply(x, lanes.stream().allMatch(l -> l.ready) ? 200 : 503, "application/json", sb.append("]}").toString());
    });
    s.createContext("/history/", x -> {
      try {
        String[] path = x.getRequestURI().getPath().split("/");   // "", history, accounts, {id}, fills
        Map<String, String> q = query(x.getRequestURI().getRawQuery());
        if (path.length == 5 && path[2].equals("accounts")) {
          String account = "" + Integer.parseUnsignedInt(path[3]);
          switch (path[4]) {
            case "fills" -> json(x, store, ClickHouseStore.FILLS, Map.of("account", account, "day", day(q),
                "limit", "" + Math.min(1000, Integer.parseUnsignedInt(q.getOrDefault("limit", "100"))),
                "offset", "" + Long.parseUnsignedLong(q.getOrDefault("offset", "0"))));
            case "tca" -> json(x, store, ClickHouseStore.TCA, Map.of("account", account, "from", time(q, "from"), "to", time(q, "to")));
            case "positions" -> json(x, store, ClickHouseStore.POSITIONS, Map.of("account", account, "asOf", time(q, "asOf")));
            default -> reply(x, 404, "text/plain", "unknown history path");
          }
        } else if (path.length == 3 && path[2].equals("vwap")) {
          json(x, store, ClickHouseStore.VWAP, Map.of("from", time(q, "from"), "to", time(q, "to")));
        } else if (path.length == 4 && path[2].equals("export") && (path[3].equals("trades") || path[3].equals("orders"))) {
          String sql = path[3].equals("trades") ? ClickHouseStore.EXPORT_TRADES : ClickHouseStore.EXPORT_ORDERS;
          try (InputStream in = store.stream(sql, Map.of("day", day(q)))) {
            x.getResponseHeaders().add("Content-Type", "text/csv");
            x.sendResponseHeaders(200, 0);                      // chunked: the whole day
            try (OutputStream out = x.getResponseBody()) { in.transferTo(out); }
          }
        } else {
          reply(x, 404, "text/plain", "unknown history path");
        }
      } catch (IllegalArgumentException | java.time.DateTimeException bad) {
        reply(x, 400, "text/plain", "bad request: " + bad.getMessage());
      } catch (Exception e) {
        reply(x, 502, "text/plain", e.toString());
      }
    });
    s.start();
  }

  static void json(HttpExchange x, ClickHouseStore store, String sql, Map<String, String> params) throws Exception {
    reply(x, 200, "application/json", store.query(sql, params));
  }

  static String day(Map<String, String> q) {
    String d = q.get("day");
    return (d == null ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(d)).toString();
  }

  /** An ISO-8601 instant or epoch ms, as ClickHouse DateTime64(3) text in UTC. */
  static String time(Map<String, String> q, String name) {
    String v = q.get(name);
    if (v == null) throw new IllegalArgumentException("missing " + name);
    Instant t = v.chars().allMatch(Character::isDigit) ? Instant.ofEpochMilli(Long.parseLong(v)) : Instant.parse(v);
    return CH_TIME.format(t);
  }

  static Map<String, String> query(String raw) {
    Map<String, String> m = new HashMap<>();
    if (raw == null) return m;
    for (String kv : raw.split("&")) {
      int i = kv.indexOf('=');
      if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
    }
    return m;
  }

  static void reply(HttpExchange x, int status, String type, String body) throws java.io.IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().add("Content-Type", type);
    x.sendResponseHeaders(status, b.length);
    try (OutputStream out = x.getResponseBody()) { out.write(b); }
  }
}
