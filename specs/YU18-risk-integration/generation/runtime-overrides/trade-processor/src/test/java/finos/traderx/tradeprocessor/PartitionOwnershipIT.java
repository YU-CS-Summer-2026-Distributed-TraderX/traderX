package finos.traderx.tradeprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-DRS-45 on the shipped schema with a real JetStream: trade-processor as separate processes (child JVMs) on one
 * NATS and one MariaDB, P = 4, one stream per partition. Replica A owns {@code replica:0/2} (0-1) and B owns
 * {@code 2-3}; 400 sides over every partition. Each answers accounting for its own partitions only, and the union is
 * every side once. C claiming partition 0 while A lives is refused (PARTITION_OWNED, exit 3) and reads nothing. After A
 * stops cleanly (its rows released), C' owning 0-1 starts at once, resumes A's cursors, and the union is still exact.
 *
 * <p>FR-DRS-46, the read router: 200 orders over every account (100 then filled). Every account's
 * {@code /accounts/{id}/orders} in each shape (open, {@code status=all}, {@code clientOrderKey}) answers the same through
 * either replica as from its owner; an already-routed read at a non-owner is 421; with A stopped, a read of A's account
 * through B is 503, not an empty list. A replica's id is its {@code 127.0.0.1:<port>}, so {@code http://{replica}} reaches it.
 */
@Tag("integration")
@Testcontainers
@Timeout(300)
class PartitionOwnershipIT {
  @Container static final MariaDBContainer<?> DB = new MariaDBContainer<>(
      "mariadb@sha256:f2f5e00999bd335343e09091e27e39c9386eb1822efcd0e0bac40b0003fa47e3")
      .withCommand("--lower-case-table-names=1");
  @Container static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.10-alpine").withCommand("-js")
      .withExposedPorts(4222);
  private static final Path CONFIGMAP = Path.of("../kubernetes-runtime/manifests/base/database-init-configmap.yaml");
  private static final int P = 4, ACCOUNTS = 20;
  private static final long TTL_MS = 3000;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();
  private final List<Process> children = new ArrayList<>();

  record Replica(String id, Process process, int port, Path log) {}

  @BeforeAll static void schema() throws Exception {
    execute(sql("001-initialSchema.sql"));
    execute(sql("900-migrations.sql"));
    StringBuilder accounts = new StringBuilder("INSERT IGNORE INTO accounts(id,displayname) VALUES ");
    for (int a = 1; a <= ACCOUNTS; a++) { accounts.append(a == 1 ? "" : ",").append("(").append(a).append(",'a").append(a).append("')"); }
    execute(accounts.toString());
  }

  @AfterEach void stop() { children.forEach(Process::destroyForcibly); }

  @Test void eachPartitionIsReadByExactlyOneReplicaAndASecondClaimIsRefused() throws Exception {
    try (Connection nc = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222))) {
      Replica a = start("tp-a", "replica:0/2");
      Replica b = start("tp-b", "2-3");
      answering(a); answering(b);                                            // the streams exist before the sides
      for (long n = 1; n <= 400; n++) { side(nc, n); }
      for (int i = 1; i <= 200; i++) { order(nc, i, "NEW"); }
      for (int i = 1; i <= 100; i++) { order(nc, i, "FILLED"); }
      nc.flush(Duration.ofSeconds(10));
      JsonNode ja = caughtUp(a), jb = caughtUp(b);
      assertThat(fieldNames(ja.get("trades"))).as("A answers for its partitions only").containsExactly("0", "1");
      assertThat(fieldNames(jb.get("trades"))).as("B answers for its partitions only").containsExactly("2", "3");
      assertThat(ja.get("replica").asText()).isEqualTo(a.id());
      assertThat(json.convertValue(ja.get("owned"), int[].class)).containsExactly(0, 1);
      assertThat(sides(ja) + sides(jb)).as("the union holds every side once").isEqualTo(400);
      assertThat(dups(ja) + dups(jb)).isZero();
      JsonNode status = get(b, "/v2/projection-feed");
      assertThat(fieldNames(status.at("/trades/partitions"))).as("B's live views").containsExactly("2", "3");
      assertThat(owners()).as("projection_partition_owner").isEqualTo(Map.of(0, a.id(), 1, a.id(), 2, b.id(), 3, b.id()));

      for (int acct = 1; acct <= ACCOUNTS; acct++) {                       // FR-DRS-46: the same answer through either replica
        Replica owner = Math.floorMod(acct, P) < 2 ? a : b, other = owner == a ? b : a;
        for (String shape : new String[] {"", "?status=all", "?clientOrderKey=1"}) {
          String path = "/accounts/" + acct + "/orders" + shape;
          String direct = body(owner, path, 200);
          assertThat(body(other, path, 200)).as("account " + acct + shape + " through the non-owner").isEqualTo(direct);
        }
        assertThat(json.readTree(body(other, "/accounts/" + acct + "/orders?status=all", 200))).as("account " + acct).hasSize(10);
        assertThat(code(other, "/accounts/" + acct + "/orders", "X-TraderX-Routed")).as("a routed read is not routed again").isEqualTo(421);
      }
      assertThat(get(a, "/v2/projection-feed").at("/ownership/orderReads/forwarded").asLong()).as("B's ten accounts, four reads each").isEqualTo(40);

      Replica c = start("tp-c", "0");
      assertThat(c.process().waitFor(60, TimeUnit.SECONDS)).as("C stops on its own").isTrue();
      assertThat(c.process().exitValue()).as("a refusal exits 3").isEqualTo(3);
      String out = Files.readString(c.log());
      assertThat(out).contains("PARTITION_OWNED replica " + c.id()).contains("partition 0 is owned by replica " + a.id());
      assertThat(out).as("C never read a partition").doesNotContain("trade store: partition 0 loaded");
      assertThat(owners().get(0)).isEqualTo(a.id());

      a.process().destroy();                                                 // SIGTERM: a clean stop releases 0-1
      assertThat(a.process().waitFor(60, TimeUnit.SECONDS)).isTrue();
      assertThat(owners().get(0)).as("released on a clean stop").isEmpty();
      assertThat(body(b, "/accounts/4/orders", 503)).as("no live owner is 503, never an empty list").contains("partition 0");
      for (long n = 401; n <= 600; n++) { side(nc, n); }
      nc.flush(Duration.ofSeconds(10));
      long s0 = System.nanoTime();
      Replica c2 = start("tp-c", "0-1");
      JsonNode jc = caughtUp(c2);
      assertThat((System.nanoTime() - s0) / 1_000_000).as("no TTL wait after a release").isLessThan(60_000);
      assertThat(Files.readString(c2.log())).doesNotContain("held by a live replica");
      jb = caughtUp(b);
      assertThat(sides(jc) + sides(jb)).as("after the handover the union still holds every side once").isEqualTo(600);
      assertThat(dups(jc) + dups(jb)).isZero();
      assertThat(owners().get(0)).isEqualTo(c2.id());
    }
  }

  private static long sides(JsonNode acct) { long s = 0; for (JsonNode t : acct.get("trades")) { s += t.get("sides").asLong(); } return s; }
  private static long dups(JsonNode acct) { long s = 0; for (JsonNode t : acct.get("trades")) { s += t.get("duplicates").asLong(); } return s; }
  private static List<String> fieldNames(JsonNode n) { List<String> out = new ArrayList<>(); n.fieldNames().forEachRemaining(out::add); return out; }

  private Map<Integer, String> owners() throws Exception {
    Map<Integer, String> out = new TreeMap<>();
    try (var c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()); var s = c.createStatement();
         var rs = s.executeQuery("SELECT partition_id, replica_id FROM projection_partition_owner")) {
      while (rs.next()) { out.put(rs.getInt(1), rs.getString(2)); }
    }
    return out;
  }

  private String body(Replica r, String path, int want) throws Exception {
    var resp = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + r.port() + path)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(resp.statusCode()).as(r.id() + path + " " + resp.body()).isEqualTo(want);
    return resp.body();
  }

  private int code(Replica r, String path, String header) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + r.port() + path)).header(header, "it").build(),
        HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  /** Order i on account i mod 20 + 1, as the order lane publishes it (SnapshotReadModelIT's shape). */
  private static void order(Connection nc, int i, String status) {
    int acct = i % ACCOUNTS + 1;
    long now = System.currentTimeMillis();
    String body = "{\"topic\":\"/orders\",\"from\":\"cluster-bridge\",\"type\":\"OrderUpdate\",\"date\":" + now
        + ",\"payload\":{\"id\":\"pow-" + i + "\",\"accountId\":" + acct + ",\"security\":\"POW\",\"side\":\"Buy\",\"quantity\":10,"
        + "\"remainingQuantity\":" + ("NEW".equals(status) ? 10 : 0) + ",\"limitPrice\":100.000000,\"status\":\"" + status
        + "\",\"createdAt\":" + now + ",\"updatedAt\":" + now + "}}";
    nc.publish("/orders." + Math.floorMod(acct, P), body.getBytes(StandardCharsets.UTF_8));
  }

  /** One legacy trade side n on its account's partition subject, as the bridge's receipt lane publishes it. */
  private static void side(Connection nc, long n) {
    int acct = (int) (n % ACCOUNTS) + 1;
    boolean buy = n % 2 == 0;
    String body = "{\"topic\":\"/trades\",\"from\":\"cluster-bridge\",\"type\":\"TradeOrder\",\"date\":" + System.currentTimeMillis()
        + ",\"payload\":{\"id\":\"" + n + (buy ? "-B" : "-S") + "\",\"state\":\"New\",\"security\":\"POW\",\"quantity\":10,"
        + "\"price\":100.000000,\"accountId\":" + acct + ",\"side\":\"" + (buy ? "Buy" : "Sell") + "\",\"sourceOrderId\":\"o" + n + "\"}}";
    nc.publish("/trades." + Math.floorMod(acct, P), body.getBytes(StandardCharsets.UTF_8));
  }

  private Replica start(String id, String owned) throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
    Path log = Files.createTempFile("partition-ownership-" + id, ".log");
    String replicaId = "127.0.0.1:" + port;   // FR-DRS-46: http://{replica} reaches it
    List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx512m", "-cp", System.getProperty("java.class.path"), TradeProcessorApplication.class.getName(),
        // Command-line arguments: they outrank the test classpath's own datasource defaults (an embedded H2).
        "--server.port=" + port,
        "--spring.datasource.url=" + DB.getJdbcUrl(), "--spring.datasource.username=" + DB.getUsername(),
        "--spring.datasource.password=" + DB.getPassword(), "--spring.datasource.driverClassName=org.mariadb.jdbc.Driver",
        "--spring.jpa.database-platform=org.hibernate.dialect.MariaDBDialect",
        "--spring.jpa.hibernate.ddl-auto=none", "--spring.sql.init.mode=never",
        "--nats.address=nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222),
        "--traderx.feed.mode=jetstream", "--traderx.feed.partition-count=" + P, "--traderx.feed.fetch-wait-ms=50",
        "--traderx.feed.stream-layout=partition", "--traderx.feed.owned=" + owned, "--traderx.feed.replica-id=" + replicaId,
        "--traderx.feed.replica-url=http://{replica}",
        "--traderx.feed.owner-ttl-ms=" + TTL_MS,
        "--traderx.trade-projection.mode=snapshot", "--traderx.trade-projection.snapshot-ms=300",
        "--traderx.order-feed.persist=false", "--traderx.order-view.snapshot-ms=300",
        "--recon.poll.interval-ms=3600000", "--settlement.sweep.interval-ms=3600000",
        "--order-matcher.base-url=http://127.0.0.1:1", "--eod.event.enabled=false"));
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    children.add(p);
    return new Replica(replicaId, p, port, log);
  }

  private JsonNode get(Replica r, String path) throws Exception {
    var resp = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + r.port() + path)).timeout(Duration.ofSeconds(5)).build(),
        HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) { throw new IllegalStateException(path + " " + resp.statusCode()); }
    return json.readTree(resp.body());
  }

  private void answering(Replica r) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
    while (true) {
      try { get(r, "/v2/projection-feed/accounting"); return; } catch (Exception notYet) { }
      if (!r.process().isAlive() || System.nanoTime() > deadline) { throw new AssertionError(r.id() + " never answered:\n" + Files.readString(r.log())); }
      Thread.sleep(250);
    }
  }

  /** This replica's committed accounting once each of its trade cursors reached its subject's last message. */
  private JsonNode caughtUp(Replica r) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
    Object last = null;
    while (true) {
      if (!r.process().isAlive()) { throw new AssertionError(r.id() + " exited " + r.process().exitValue() + ":\n" + Files.readString(r.log())); }
      try {
        JsonNode n = get(r, "/v2/projection-feed/accounting");
        boolean done = n.get("trades").size() > 0;
        for (JsonNode t : n.get("trades")) { done &= t.get("streamLast").asLong() > 0 && t.get("cursor").asLong() >= t.get("streamLast").asLong(); }
        for (JsonNode o : n.get("orders")) { done &= o.get("cursor").asLong() >= o.get("streamLast").asLong(); }
        if (done) { return n; }
        last = n;
      } catch (Exception notYet) { last = notYet; }
      if (System.nanoTime() > deadline) { throw new AssertionError(r.id() + " not caught up within 120 s (last: " + last + ")"); }
      Thread.sleep(250);
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
