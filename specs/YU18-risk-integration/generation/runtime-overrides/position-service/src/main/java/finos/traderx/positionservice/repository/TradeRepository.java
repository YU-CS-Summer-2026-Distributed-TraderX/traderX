package finos.traderx.positionservice.repository;

import finos.traderx.positionservice.model.Trade;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The blotter (ADR-102 cutover, 2026-11-08): booked trades come from the history store, ClickHouse {@code trades},
 * the trade system of record, newest first. SQL {@code trades} holds only exceptions, which replace the history
 * row of the same id: the sides booking refused ({@code Rejected}, with their reason) and operator-forced
 * settlements ({@code Settled}). A booked side's state is the T+N rule trade-processor books with: Settled once its
 * trade date plus {@code settlement.t-plus-days} business days has passed, else Processing.
 *
 * <p>Only the active scope's history is in the store (an earlier epoch's moved to {@code arc_<label>_*},
 * FR-THT-08); any other scope answers the SQL rows it holds, as before the cutover (its old blotter window, managed
 * run fixtures). Every caller value is a ClickHouse query parameter.
 */
@Repository
public class TradeRepository {

  private static final RowMapper<Trade> TRADE_ROW_MAPPER = (rs, rowNum) -> {
    Trade trade = new Trade();
    trade.setProjectionScope(rs.getString("ProjectionScope"));
    trade.setId(rs.getString("ID"));
    trade.setAccountId(rs.getInt("AccountID"));
    trade.setSecurity(rs.getString("Security"));
    trade.setSide(rs.getString("Side"));
    trade.setState(rs.getString("State"));
    trade.setQuantity(rs.getInt("Quantity"));
    BigDecimal price = rs.getBigDecimal("Price");
    trade.setPrice(price);
    trade.setUpdated(rs.getTimestamp("Updated"));
    trade.setCreated(rs.getTimestamp("Created"));
    // YU16 (FR-CDM23): rejection attribution; both columns exist from this state's migration.
    trade.setRejectionReason(rs.getString("RejectionReason"));
    trade.setSourceOrderId(rs.getString("SourceOrderId"));
    return trade;
  };
  private static final String COLUMNS = "select ID, AccountID, Security, Side, State, Quantity, Price, Updated, Created,"
      + " RejectionReason, SourceOrderId, ProjectionScope from Trades";
  private static final String EXCEPTION = "State in ('Rejected','Settled')";
  // The unfiltered list reads the newest date partition only: max(toDate(ts)) answers from the partition index.
  static final String PAGE = "SELECT toUnixTimestamp64Milli(ts), trade_seq, side, account, security, qty, px, order_ref,"
      + " id_prefix, order_epoch FROM trades WHERE %s ORDER BY ts DESC, seq DESC LIMIT {n:UInt32} FORMAT TabSeparated";

  private final JdbcTemplate jdbcTemplate;
  private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5)).build();
  private final String history;
  private final int limit;
  private final int tPlusDays;

  public TradeRepository(JdbcTemplate jdbcTemplate,
      @Value("${traderx.history.url:http://clickhouse:8123}") String history,
      @Value("${traderx.blotter.limit:100}") int limit,
      @Value("${settlement.t-plus-days:1}") int tPlusDays) {
    this.jdbcTemplate = jdbcTemplate;
    this.history = history.endsWith("/") ? history : history + "/";
    this.limit = limit;
    this.tPlusDays = tPlusDays;
  }

  public List<Trade> findAll() { String s = active(); return s == null ? List.of() : page(s, s, null); }

  public List<Trade> findByAccountId(int accountId) { String s = active(); return s == null ? List.of() : page(s, s, accountId); }

  public List<Trade> findByScope(String scope) { return page(scope, active(), null); }

  public List<Trade> findByScopeAndAccount(String scope, int account) { return page(scope, active(), account); }

  private String active() {
    List<String> s = jdbcTemplate.queryForList("SELECT projection_scope FROM projection_active WHERE singleton_id=1", String.class);
    return s.isEmpty() ? null : s.get(0);
  }

  private List<Trade> page(String scope, String active, Integer account) {
    if (!scope.equals(active)) {
      return account == null
          ? jdbcTemplate.query(COLUMNS + " where ProjectionScope=? order by Updated desc", TRADE_ROW_MAPPER, scope)
          : jdbcTemplate.query(COLUMNS + " where ProjectionScope=? and AccountID=? order by Updated desc", TRADE_ROW_MAPPER, scope, account);
    }
    Map<String, String> params = new HashMap<>(Map.of("n", "" + limit));
    String where = "toDate(ts) = (SELECT max(toDate(ts)) FROM trades)";
    if (account != null) {
      where = "account = {a:UInt32}";
      params.put("a", "" + account);
    }
    List<Trade> rows = new ArrayList<>();
    for (String line : query(String.format(PAGE, where), params).split("\n")) {
      if (!line.isEmpty()) { rows.add(fromHistory(scope, line.split("\t", -1), Instant.now())); }
    }
    if (rows.isEmpty()) { return rows; }
    StringJoiner ids = new StringJoiner(",", "(", ")");
    List<Object> args = new ArrayList<>(List.of(scope));
    for (Trade t : rows) { ids.add("?"); args.add(t.getId()); }
    Map<String, Trade> exceptions = new HashMap<>();
    for (Trade t : jdbcTemplate.query(COLUMNS + " where ProjectionScope=? and " + EXCEPTION + " and ID in " + ids,
        TRADE_ROW_MAPPER, args.toArray())) {
      exceptions.put(t.getId(), t);
    }
    rows.replaceAll(t -> exceptions.getOrDefault(t.getId(), t));
    return rows;
  }

  /** One history row, its columns as {@link #PAGE} selects them, in its state as of {@code now}. */
  Trade fromHistory(String scope, String[] c, Instant now) {
    Trade t = new Trade();
    Date created = new Date(Long.parseLong(c[0]));
    t.setProjectionScope(scope);
    t.setId(c[8] + c[1] + "-" + c[2]);
    t.setSide("B".equals(c[2]) ? "Buy" : "Sell");
    t.setAccountId(Integer.parseInt(c[3]));
    t.setSecurity(c[4]);
    t.setQuantity(Integer.parseInt(c[5]));
    t.setPrice(new BigDecimal(c[6]).setScale(6, RoundingMode.HALF_UP));
    t.setSourceOrderId("0".equals(c[7]) ? null : c[9] + "-" + c[7]);   // 0: a market trade, no originating order
    t.setCreated(created);
    Instant settles = settlesOn(created, tPlusDays);
    boolean settled = !settles.isAfter(now);
    t.setState(settled ? "Settled" : "Processing");
    t.setUpdated(settled ? Date.from(settles) : created);
    return t;
  }

  /** trade-processor's TradeService.plusBusinessDays: the UTC trade date plus N weekdays, at midnight UTC. */
  static Instant settlesOn(Date created, int days) {
    LocalDate d = created.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    for (int added = 0; added < days; ) {
      d = d.plusDays(1);
      if (d.getDayOfWeek().getValue() < 6) { added++; }
    }
    return d.atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  private String query(String sql, Map<String, String> params) {
    StringJoiner q = new StringJoiner("&", history + "?", "");
    q.add("query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8));
    params.forEach((k, v) -> q.add("param_" + k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
    try {
      HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(q.toString())).timeout(Duration.ofSeconds(30)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() != 200) { throw new IllegalStateException("history store " + r.statusCode() + ": " + r.body().trim()); }
      return r.body();
    } catch (IOException e) {
      throw new IllegalStateException("history store unreachable: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted reading the history store", e);
    }
  }
}
