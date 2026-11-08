package finos.traderx.tradeprocessor.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ADR-102 cutover (2026-11-08): the history store (ClickHouse {@code trades}) is the trade system of record, so a
 * booked side that SQL holds no row for is read from it (the operator's forced settlement records it in SQL as an
 * exception row). Every caller value is a ClickHouse query parameter.
 */
@Component
public class HistoryStore {
  /** One booked side as the store holds it. */
  public record Side(int account, String security, String side, int quantity, String price, String sourceOrderId, long createdMillis) {}

  private static final Pattern ID = Pattern.compile("^((?:e1-[a-z0-9_]{1,25}-)?)([1-9][0-9]{0,18})-([BS])$");
  // trade_seq is not in the sort key; history-feed's minmax index on it (trade_seq rises with ts) skips the other granules.
  static final String SIDE = "SELECT account, security, qty, px, order_ref, order_epoch, toUnixTimestamp64Milli(ts) FROM trades"
      + " WHERE trade_seq = {s:UInt64} AND side = {d:String} AND id_prefix = {p:String} LIMIT 1 FORMAT TabSeparated";

  private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5)).build();
  private final String base;

  public HistoryStore(@Value("${traderx.history.url:http://clickhouse:8123}") String url) {
    this.base = url.endsWith("/") ? url : url + "/";
  }

  /** The side with this trade id, or null when the id is not a trade id or the store holds no such side. */
  public Side side(String tradeId) {
    Matcher m = ID.matcher(tradeId == null ? "" : tradeId);
    if (!m.matches()) { return null; }
    String row = query(SIDE, Map.of("s", m.group(2), "d", m.group(3), "p", m.group(1))).trim();
    if (row.isEmpty()) { return null; }
    String[] c = row.split("\t", -1);
    return new Side(Integer.parseInt(c[0]), c[1], "B".equals(m.group(3)) ? "Buy" : "Sell", Integer.parseInt(c[2]), c[3],
        "0".equals(c[4]) ? null : c[5] + "-" + c[4], Long.parseLong(c[6]));
  }

  /**
   * A trade by id for the by-id endpoints (TCA, forced settlement): its SQL row (an exception) when there is one,
   * else the booked side from the store, as a Trade with what the store holds (no state, no settlement date).
   */
  public Optional<Trade> trade(TradeRepository sql, String id) {
    Optional<Trade> row = sql.findById(id);
    if (row.isPresent()) { return row; }
    Side s = side(id);
    if (s == null) { return Optional.empty(); }
    Trade t = new Trade();
    t.setId(id);
    t.setAccountId(s.account());
    t.setSecurity(s.security());
    t.setSide("Buy".equals(s.side()) ? TradeSide.Buy : TradeSide.Sell);
    t.setQuantity(s.quantity());
    t.setPrice(new BigDecimal(s.price()));
    t.setSourceOrderId(s.sourceOrderId());
    t.setCreated(new Date(s.createdMillis()));
    return Optional.of(t);
  }

  String query(String sql, Map<String, String> params) {
    StringJoiner q = new StringJoiner("&", base + "?", "");
    q.add("query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8));
    params.forEach((k, v) -> q.add("param_" + k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
    try {
      HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(q.toString())).timeout(Duration.ofSeconds(60)).GET().build(),
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
