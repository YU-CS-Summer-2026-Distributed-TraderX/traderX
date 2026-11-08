package finos.traderx.positionservice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import finos.traderx.positionservice.model.Trade;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * ADR-102 cutover: the blotter is the history store's rows (a stub ClickHouse HTTP endpoint answering TSV in
 * {@link TradeRepository#PAGE}'s columns) with SQL's exception rows (H2) in place of the same id.
 */
class TradeRepositoryTest {
  HttpServer ch;
  final List<String> queries = new ArrayList<>();
  JdbcTemplate jdbc;
  TradeRepository repo;
  // 2026-11-02 is a Monday: T+1 settles 2026-11-03 00:00Z. The 2026-11-06 (Friday) side settles Monday 11-09.
  static final long MON = Instant.parse("2026-11-02T15:00:00Z").toEpochMilli(), FRI = Instant.parse("2026-11-06T15:00:00Z").toEpochMilli();

  @BeforeEach void up() throws Exception {
    ch = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ch.createContext("/", x -> {
      queries.add(URLDecoder.decode(x.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
      byte[] b = (FRI + "\t12\tS\t7\tIBM\t5\t187.25\t0\te1-ep7-\tep7\n"
          + MON + "\t9\tB\t7\tUST-20280630\t100\t99.5\t4411\te1-ep7-\tep7\n"
          + MON + "\t8\tB\t7\tIBM\t3\t180\t4410\t\t\n").getBytes(StandardCharsets.UTF_8);
      x.sendResponseHeaders(200, b.length);
      x.getResponseBody().write(b);
      x.close();
    });
    ch.start();
    jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:blotter" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1"));
    jdbc.execute("CREATE TABLE projection_active (singleton_id INT, projection_scope VARCHAR(64))");
    jdbc.execute("CREATE TABLE Trades (ID VARCHAR(50), AccountID INT, Security VARCHAR(50), Side VARCHAR(10), State VARCHAR(20),"
        + " Quantity INT, Price DECIMAL(18,6), Updated TIMESTAMP, Created TIMESTAMP, RejectionReason VARCHAR(200),"
        + " SourceOrderId VARCHAR(50), ProjectionScope VARCHAR(64))");
    jdbc.update("INSERT INTO projection_active VALUES (1, 'run-ep7')");
    jdbc.update("INSERT INTO Trades VALUES ('e1-ep7-9-B', 7, 'UST-20280630', 'Buy', 'Rejected', 100, 99.5, NOW(), NOW(),"
        + " 'Bond reference metadata is unavailable', 'ep7-4411', 'run-ep7')");
    // a pre-cutover window row: Processing, never an exception, so the history row stands
    jdbc.update("INSERT INTO Trades VALUES ('e1-ep7-12-S', 7, 'IBM', 'Sell', 'Processing', 5, 187.25, NOW(), NOW(), NULL, NULL, 'run-ep7')");
    jdbc.update("INSERT INTO Trades VALUES ('old-1-B', 7, 'IBM', 'Buy', 'Processing', 1, 1, NOW(), NOW(), NULL, NULL, 'run-old')");
    repo = new TradeRepository(jdbc, "http://127.0.0.1:" + ch.getAddress().getPort(), 100, 1);
  }

  @AfterEach void down() { ch.stop(0); }

  @Test void anAccountsBlotterIsTheHistoryWithSqlExceptionsInPlace() {
    List<Trade> t = repo.findByAccountId(7);
    assertEquals(List.of("e1-ep7-12-S", "e1-ep7-9-B", "8-B"), t.stream().map(Trade::getId).toList());
    assertTrue(queries.get(0).contains("account = {a:UInt32}") && queries.get(0).contains("param_a=7"), queries.get(0));
    assertEquals("Rejected", t.get(1).getState());
    assertEquals("Bond reference metadata is unavailable", t.get(1).getRejectionReason());
    assertEquals("ep7-4411", t.get(1).getSourceOrderId());
    assertNull(t.get(0).getSourceOrderId(), "order_ref 0 is a market trade");
    assertEquals("Sell", t.get(0).getSide());
    assertEquals("187.250000", t.get(0).getPrice().toPlainString());
    assertEquals("-4410", t.get(2).getSourceOrderId().substring(t.get(2).getSourceOrderId().indexOf('-')), "a legacy row has no epoch");
  }

  @Test void aBookedSidesStateIsTheTPlusNRule() {
    String[] fri = {"" + FRI, "12", "S", "7", "IBM", "5", "187.25", "0", "", ""};
    assertEquals("Processing", repo.fromHistory("s", fri, Instant.parse("2026-11-08T23:59:59Z")).getState());   // a weekend
    Trade monday = repo.fromHistory("s", fri, Instant.parse("2026-11-09T00:00:00Z"));
    assertEquals("Settled", monday.getState());
    assertEquals(Instant.parse("2026-11-09T00:00:00Z"), monday.getUpdated().toInstant());
  }

  @Test void theUnfilteredListReadsTheNewestDayAndAnotherScopeItsOwnSqlRows() {
    assertEquals(3, repo.findAll().size());
    assertTrue(queries.get(0).contains("toDate(ts) = (SELECT max(toDate(ts)) FROM trades)"), queries.get(0));
    queries.clear();
    assertEquals(List.of("old-1-B"), repo.findByScopeAndAccount("run-old", 7).stream().map(Trade::getId).toList());
    assertTrue(queries.isEmpty(), "an archived epoch's history is not in the live store");
  }
}
