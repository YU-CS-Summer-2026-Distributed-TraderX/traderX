package finos.traderx.tradeprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-DRS-29 and FR-DRS-30, the production snapshot read model across a restart, on the shipped schema with a real
 * JetStream: P = 2, trades in the trade store, orders in the memory-only view with its SQL snapshot.
 *
 * <p>Lifetime A books 1,000 sides and opens 300 orders (100 then fill). It stops; the outage outlasts the view's
 * replay window (1 s); 500 more sides, one second copy of side 1200 and 50 cancels arrive while it is down.
 * Lifetime B must then hold: every order opened in lifetime A that is still open (the replay window alone would
 * have lost them), the committed accounting equal to the closed form over sides 1..1500 with the copy refused and
 * counted, and every position equal to the net of the sides.
 */
@Tag("integration")
@Testcontainers
@Timeout(300)
class SnapshotReadModelIT {
  @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>(
      "mariadb@sha256:f2f5e00999bd335343e09091e27e39c9386eb1822efcd0e0bac40b0003fa47e3")
      .withCommand("--lower-case-table-names=1");
  @Container static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.10-alpine").withCommand("-js")
      .withExposedPorts(4222);
  private static final Path CONFIGMAP = Path.of("../kubernetes-runtime/manifests/base/database-init-configmap.yaml");
  private static final int P = 2, ACCOUNTS = 20;
  // A/B: -Dsrm.viewSnapshotMs=0 is the replay-window view; the open orders of lifetime A must then be missing.
  private static final String VIEW_SNAPSHOT_MS = System.getProperty("srm.viewSnapshotMs", "500");
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll static void schema() throws Exception {
    execute(sql("001-initialSchema.sql"));
    execute(sql("900-migrations.sql"));
    StringBuilder accounts = new StringBuilder("INSERT IGNORE INTO accounts(id,displayname) VALUES ");
    for (int a = 1; a <= ACCOUNTS; a++) { accounts.append(a == 1 ? "" : ",").append("(").append(a).append(",'a").append(a).append("')"); }
    execute(accounts.toString());
  }

  @Test void openOrdersAndExactlyOnceAccountingSurviveARestartLongerThanTheReplayWindow() throws Exception {
    Map<String, Integer> net = new HashMap<>();
    try (Connection nc = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222))) {
      ConfigurableApplicationContext a = start();
      for (long n = 1; n <= 1000; n++) { side(nc, n, net); }
      for (int i = 1; i <= 300; i++) { order(nc, i, "NEW"); }
      for (int i = 1; i <= 100; i++) { order(nc, i, "FILLED"); }
      nc.flush(Duration.ofSeconds(10));
      JsonNode first = caughtUp(a);
      assertThat(first.at("/trades/0/sides").asLong() + first.at("/trades/1/sides").asLong()).isEqualTo(1000);
      a.close();

      for (long n = 1001; n <= 1500; n++) { side(nc, n, net); }
      side(nc, 1200, null);                                                   // a second copy: must not book
      for (int i = 101; i <= 150; i++) { order(nc, i, "CANCELED"); }
      nc.flush(Duration.ofSeconds(10));
      Thread.sleep(1500);                                                     // the outage outlasts replay-seconds=1

      ConfigurableApplicationContext b = start();
      try {
        JsonNode acct = caughtUp(b);
        long sides = 0, dups = 0, max = 0;
        BigInteger sum = BigInteger.ZERO, sq = BigInteger.ZERO, mod = BigInteger.ONE.shiftLeft(64);
        for (int p = 0; p < P; p++) {
          JsonNode t = acct.at("/trades/" + p);
          sides += t.get("sides").asLong();
          dups += t.get("duplicates").asLong();
          max = Math.max(max, t.get("maxSeq").asLong());
          sum = sum.add(new BigInteger(t.get("sumSeq").asText())).mod(mod);
          sq = sq.add(new BigInteger(t.get("sumSq").asText())).mod(mod);
        }
        BigInteger t = BigInteger.valueOf(1500);
        assertThat(sides).as("sides booked").isEqualTo(1500);
        assertThat(dups).as("second copies refused").isEqualTo(1);
        assertThat(max).isEqualTo(1500);
        assertThat(sum).isEqualTo(t.multiply(t.add(BigInteger.ONE)).shiftRight(1).mod(mod));
        assertThat(sq).isEqualTo(t.multiply(t.add(BigInteger.ONE)).multiply(t.shiftLeft(1).add(BigInteger.ONE))
            .divide(BigInteger.valueOf(6)).mod(mod));

        long committedResting = 0;
        for (int p = 0; p < P; p++) { committedResting += acct.at("/orders/" + p + "/resting").asLong(); }
        OrderView view = b.getBean(OrderFeedHandler.class).view();
        assertThat(view.resting()).as("orders still open in the view after the restart").isEqualTo(150);
        assertThat(committedResting).as("orders still open in the committed snapshot").isEqualTo(150);
        assertThat(view.get(account(200), "srm-200")).as("an order opened before the outage").isNotNull();

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
        Map<String, Integer> positions = new HashMap<>();
        jdbc.query("SELECT accountid, security, quantity FROM positions WHERE security='SRM'",
            rs -> { positions.put(rs.getInt(1) + "|" + rs.getString(2), rs.getInt(3)); });
        assertThat(positions).as("positions = the net of sides 1..1500, each once").isEqualTo(net);
      } finally {
        b.close();
      }
    }
  }

  private static int account(long n) { return (int) (n % ACCOUNTS) + 1; }

  /** One legacy trade side n on its account's partition subject, as the bridge's receipt lane publishes it. */
  private static void side(Connection nc, long n, Map<String, Integer> net) {
    int acct = account(n);
    boolean buy = n % 2 == 0;
    String body = "{\"topic\":\"/trades\",\"from\":\"cluster-bridge\",\"type\":\"TradeOrder\",\"date\":" + System.currentTimeMillis()
        + ",\"payload\":{\"id\":\"" + n + (buy ? "-B" : "-S") + "\",\"state\":\"New\",\"security\":\"SRM\",\"quantity\":10,"
        + "\"price\":100.000000,\"accountId\":" + acct + ",\"side\":\"" + (buy ? "Buy" : "Sell") + "\",\"sourceOrderId\":\"o" + n + "\"}}";
    nc.publish("/trades." + Math.floorMod(acct, P), body.getBytes(StandardCharsets.UTF_8));
    if (net != null) { net.merge(acct + "|SRM", buy ? 10 : -10, Integer::sum); }
  }

  private static void order(Connection nc, int i, String status) {
    int acct = account(i);
    int remaining = "NEW".equals(status) ? 10 : 0;
    long now = System.currentTimeMillis();
    String body = "{\"topic\":\"/orders\",\"from\":\"cluster-bridge\",\"type\":\"OrderUpdate\",\"date\":" + now
        + ",\"payload\":{\"id\":\"srm-" + i + "\",\"accountId\":" + acct + ",\"security\":\"SRM\",\"side\":\"Buy\",\"quantity\":10,"
        + "\"remainingQuantity\":" + remaining + ",\"limitPrice\":100.000000,\"status\":\"" + status + "\",\"createdAt\":" + now
        + ",\"updatedAt\":" + now + "}}";
    nc.publish("/orders." + Math.floorMod(acct, P), body.getBytes(StandardCharsets.UTF_8));
  }

  private ConfigurableApplicationContext start() {
    // Command-line arguments: they outrank the test classpath's own datasource defaults (an embedded H2).
    return new SpringApplicationBuilder(TradeProcessorApplication.class).run(
        "--server.port=0",
        "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
        "--spring.datasource.password=" + DB.getPassword(), "--spring.datasource.driverClassName=org.mariadb.jdbc.Driver",
        "--spring.jpa.database-platform=org.hibernate.dialect.MariaDBDialect",
        "--spring.jpa.hibernate.ddl-auto=none", "--spring.sql.init.mode=never",
        "--nats.address=nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222),
        "--traderx.feed.mode=jetstream", "--traderx.feed.partition-count=" + P, "--traderx.feed.fetch-wait-ms=50",
        "--traderx.trade-projection.mode=snapshot", "--traderx.trade-projection.snapshot-ms=500",
        "--traderx.order-feed.persist=false", "--traderx.order-view.replay-seconds=1",
        "--traderx.order-view.snapshot-ms=" + VIEW_SNAPSHOT_MS,
        "--recon.poll.interval-ms=3600000", "--settlement.sweep.interval-ms=3600000",
        "--order-matcher.base-url=http://127.0.0.1:1");
  }

  /** The committed accounting once every partition's trade (and, if snapshotted, order) cursor reached the stream. */
  private JsonNode caughtUp(ConfigurableApplicationContext ctx) throws Exception {
    int port = ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
    JsonNode[] last = new JsonNode[1];
    await(() -> {
      try {
        var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v2/projection-feed/accounting")).build(),
            HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) { return false; }
        JsonNode n = json.readTree(r.body());
        last[0] = n;
        for (int p = 0; p < P; p++) {
          JsonNode t = n.at("/trades/" + p);
          if (t.get("streamLast").asLong() == 0 || t.get("cursor").asLong() < t.get("streamLast").asLong()) { return false; }
          if (!"0".equals(VIEW_SNAPSHOT_MS)) {
            JsonNode o = n.at("/orders/" + p);
            if (o.isMissingNode() || o.get("cursor").asLong() < o.get("streamLast").asLong()) { return false; }
          }
        }
        return true;
      } catch (Exception x) { return false; }
    });
    if ("0".equals(VIEW_SNAPSHOT_MS)) { Thread.sleep(1500); }   // the replay-window view has no cursor to wait for
    return last[0];
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) { throw new AssertionError("not caught up within 90 s"); }
      Thread.sleep(200);
    }
  }

  private static void execute(String sql) throws Exception {
    String url = DB.getJdbcUrl() + (DB.getJdbcUrl().contains("?") ? "&" : "?") + "allowMultiQueries=true";
    try (var c = DriverManager.getConnection(url, DB.getUsername(), DB.getPassword()); var s = c.createStatement()) {
      s.execute(sql);
    }
  }

  /** One block-scalar key of the shipped configmap (TradeSnapshotStoreConcurrencyIT's extraction). */
  private static String sql(String key) throws Exception {
    StringBuilder sql = new StringBuilder();
    boolean in = false;
    for (String line : Files.readAllLines(CONFIGMAP)) {
      if (line.equals("  " + key + ": |")) { in = true; continue; }
      if (in) {
        if (!line.isBlank() && !line.startsWith("    ")) { break; }
        sql.append(line.length() > 4 ? line.substring(4) : "").append('\n');
      }
    }
    assertThat(sql).as(key).isNotEmpty();
    return sql.toString();
  }
}
