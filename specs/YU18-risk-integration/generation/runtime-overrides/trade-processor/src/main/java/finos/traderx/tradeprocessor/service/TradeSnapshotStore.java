package finos.traderx.tradeprocessor.service;

import finos.traderx.tradeprocessor.TradeView;
import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.Trade;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * YU18 trade store (docs/engineering/downstream-at-400k.md §7): the SQL side of {@link TradeView}.
 * One transaction per snapshot: the changed positions (multi-row upsert), the sides booking refused
 * since the last one (SQL {@code trades} holds only those exceptions since the ADR-102 cutover, 2026-11-08:
 * booked trades are the history store's), the stream cursor, and the scope's counters. The cursor row is what makes a restart exact: the view reloads
 * the snapshot and replays the stream from {@code stream_seq + 1}.
 *
 * <p>Plain JDBC on purpose: these are bulk statements on a timer, not entities.
 */
@Service
public final class TradeSnapshotStore {
  public record Cursor(long streamSeq, long consensusSeq) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ProjectionWriteLock lock;

  public TradeSnapshotStore(JdbcTemplate jdbc, TransactionTemplate transactions, ProjectionWriteLock lock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.lock = lock;
  }

  public Cursor loadCursor(String scope, int partition) {
    List<Cursor> rows = jdbc.query(
        "SELECT stream_seq, consensus_seq FROM projection_cursor WHERE projection_scope=? AND partition_id=?",
        (rs, i) -> new Cursor(rs.getLong(1), rs.getLong(2)), scope, partition);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** FR-DRS-36: creation time (epoch ns) of the stream {@code cursorScope}'s cursor was taken on; null if never bound. */
  public static Long boundStream(JdbcTemplate jdbc, String cursorScope, int partition) {
    List<Long> rows = jdbc.query("SELECT stream_created_ns FROM projection_cursor WHERE projection_scope=? AND partition_id=?",
        (rs, i) -> (Long) rs.getObject(1, Long.class), cursorScope, partition);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** Binds the cursor to the stream it resumes on, before the first message is applied (a new row starts at 0). */
  public static void bindStream(JdbcTemplate jdbc, String cursorScope, int partition, long createdNs) {
    jdbc.update("INSERT INTO projection_cursor(projection_scope,partition_id,stream_seq,consensus_seq,stream_created_ns)"
        + " VALUES (?,?,0,0,?) ON DUPLICATE KEY UPDATE stream_created_ns=VALUES(stream_created_ns)", cursorScope, partition, createdNs);
  }

  public Long boundStream(String scope, int partition) { return boundStream(jdbc, scope, partition); }
  public void bindStream(String scope, int partition, long createdNs) { bindStream(jdbc, scope, partition, createdNs); }

  public List<Position> loadPositions(String scope) {
    return jdbc.query("SELECT projectionscope, accountid, security, quantity, averagecostbasis, updated FROM positions WHERE projectionscope=?",
        (rs, i) -> {
          Position p = new Position();
          p.setProjectionScope(rs.getString(1));
          p.setAccountId(rs.getInt(2));
          p.setSecurity(rs.getString(3));
          p.setQuantity(rs.getInt(4));
          p.setAverageCostBasis(rs.getBigDecimal(5));
          p.setUpdated(rs.getTimestamp(6));
          return p;
        }, scope);
  }

  /** FR-DRS-29: the partition's exactly-once accounting as of its cursor, or null before its first snapshot. */
  public TradeView.Accounting loadAccounting(String scope, int partition) {
    List<TradeView.Accounting> rows = jdbc.query("SELECT sides, max_seq, sum_seq, sum_sq, duplicates, epoch FROM projection_accounting"
        + " WHERE projection_scope=? AND partition_id=?", (rs, i) -> new TradeView.Accounting(rs.getLong(1), rs.getLong(2),
        rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getString(6)), scope, partition);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /**
   * FR-DRS-29: per partition, the committed cursor and accounting, i.e. what a restart resumes from. The sums are
   * unsigned decimal strings (they wrap at 2^64; a JSON number would lose digits).
   */
  public Map<Integer, Map<String, Object>> committedAccounting(String scope) {
    Map<Integer, Map<String, Object>> out = new TreeMap<>();
    jdbc.query("SELECT c.partition_id, c.stream_seq, a.sides, a.max_seq, a.sum_seq, a.sum_sq, a.duplicates, a.epoch"
        + " FROM projection_cursor c LEFT JOIN projection_accounting a ON a.projection_scope=c.projection_scope"
        + " AND a.partition_id=c.partition_id WHERE c.projection_scope=?", rs -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("cursor", rs.getLong(2));
          m.put("sides", rs.getLong(3));
          m.put("maxSeq", rs.getLong(4));
          m.put("sumSeq", Long.toUnsignedString(rs.getLong(5)));
          m.put("sumSq", Long.toUnsignedString(rs.getLong(6)));
          m.put("duplicates", rs.getLong(7));
          m.put("epoch", rs.getString(8) == null ? "" : rs.getString(8));
          out.put(rs.getInt(1), m);
        }, scope);
    return out;
  }

  /** Booked sides per UTC trade date, as of the partition's cursor (written in the same transaction). */
  public Map<LocalDate, Long> loadDays(String scope, int partition) {
    Map<LocalDate, Long> days = new TreeMap<>();
    jdbc.query("SELECT trade_date, sides FROM projection_trade_days WHERE projection_scope=? AND partition_id=?",
        rs -> { days.put(rs.getDate(1).toLocalDate(), rs.getLong(2)); }, scope, partition);
    return days;
  }

  /**
   * Settlement on the ledger: per partition and trade date, how many booked sides have settled as of
   * {@code asOf} under T+{@code tPlusDays} business days (the row path's own {@code plusBusinessDays}).
   * Read from the committed counts, so it is exact at each partition's committed cursor.
   */
  public Map<String, Object> ledgerSettlement(String scope, int tPlusDays, Instant asOf) {
    Map<Integer, Map<LocalDate, Long>> byPartition = new TreeMap<>();
    jdbc.query("SELECT partition_id, trade_date, sides FROM projection_trade_days WHERE projection_scope=?",
        rs -> { byPartition.computeIfAbsent(rs.getInt(1), k -> new TreeMap<>()).put(rs.getDate(2).toLocalDate(), rs.getLong(3)); },
        scope);
    Map<String, Object> partitions = new LinkedHashMap<>();
    long settled = 0, total = 0;
    for (var e : byPartition.entrySet()) {
      long ps = 0, pt = 0;
      Map<String, Object> days = new LinkedHashMap<>();
      for (var d : e.getValue().entrySet()) {
        Instant settles = settlesOn(d.getKey(), tPlusDays);
        boolean done = !settles.isAfter(asOf);
        days.put(d.getKey().toString(), Map.of("sides", d.getValue(), "settlesOn", settles.toString(), "settled", done));
        pt += d.getValue();
        if (done) { ps += d.getValue(); }
      }
      Cursor c = loadCursor(scope, e.getKey());
      partitions.put(String.valueOf(e.getKey()), Map.of("sides", pt, "settled", ps, "unsettled", pt - ps,
          "streamSeq", c == null ? 0 : c.streamSeq(), "days", days));
      settled += ps;
      total += pt;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("scope", scope);
    out.put("asOf", asOf.toString());
    out.put("tPlusDays", tPlusDays);
    out.put("sides", total);
    out.put("settled", settled);
    out.put("unsettled", total - settled);
    out.put("partitions", partitions);
    return out;
  }

  /** When a side traded on {@code tradeDate} settles: the row path's own T+N business-day rule. */
  static Instant settlesOn(LocalDate tradeDate, int tPlusDays) {
    return TradeService.plusBusinessDays(Date.from(tradeDate.atStartOfDay(ZoneOffset.UTC).toInstant()), tPlusDays).toInstant();
  }

  /** Committed cursor per partition: what recon requires before it calls a ledger side "projected". */
  public Map<Integer, Long> cursors(String scope) {
    Map<Integer, Long> out = new HashMap<>();
    jdbc.query("SELECT partition_id, stream_seq FROM projection_cursor WHERE projection_scope=?",
        rs -> { out.put(rs.getInt(1), rs.getLong(2)); }, scope);
    return out;
  }

  /** Write one cut atomically. Returns the number of SQL rows written. */
  public int write(String scope, int partition, TradeView.Cut cut) {
    return transactions.execute(status -> {
      // ONE lock row for every partition's snapshot (and the settlement sweep, which takes the same row).
      // Partitions own disjoint accounts, yet their window DELETEs deadlock: the range scan on
      // (projectionscope, accountid) must lock the first record past its range - the neighbouring
      // account's, i.e. another partition's - even under READ COMMITTED (TradeSnapshotStoreConcurrencyIT;
      // measured live at P=4: 8 deadlocks in a minute). A snapshot is milliseconds every few seconds, so
      // serializing them costs nothing measurable. ponytail: global row; per-account deletes by primary
      // key if snapshot writes ever become the bottleneck.
      ProjectionWriteLock.partition(0);
      lock.acquire();
      int rows = 0;
      if (!cut.positions().isEmpty()) {
        List<Object[]> args = new ArrayList<>(cut.positions().size());
        for (Position p : cut.positions()) {
          args.add(new Object[] {p.getProjectionScope(), p.getAccountId(), p.getSecurity(),
              p.getUpdated() == null ? null : new Timestamp(p.getUpdated().getTime()), p.getQuantity(), p.getAverageCostBasis()});
        }
        jdbc.batchUpdate("INSERT INTO positions(projectionscope,accountid,security,updated,quantity,averagecostbasis)"
            + " VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE updated=VALUES(updated),quantity=VALUES(quantity),"
            + "averagecostbasis=VALUES(averagecostbasis)", args);
        rows += args.size();
      }
      if (!cut.rejected().isEmpty()) {
        List<Object[]> args = new ArrayList<>(cut.rejected().size());
        for (Trade t : cut.rejected()) {
          args.add(new Object[] {t.getId(), t.getAccountId(), t.getSecurity(), t.getSide() == null ? null : t.getSide().name(),
              t.getState().name(), t.getQuantity(), t.getPrice(),
              t.getUpdated() == null ? null : new Timestamp(t.getUpdated().getTime()),
              t.getCreated() == null ? null : new Timestamp(t.getCreated().getTime()),
              t.getRejectionReason(), t.getSourceOrderId(), t.getProjectionScope(), t.getClusterEpoch(),
              t.getEventIdScheme(), t.getRunDescriptorHash(), t.getConsensusSequence()});
        }
        // INSERT IGNORE: a replay after a failed snapshot re-books the same id.
        jdbc.batchUpdate("INSERT IGNORE INTO trades(id,accountid,security,side,state,quantity,price,updated,created,"
            + "rejectionreason,sourceorderid,projectionscope,clusterepoch,eventidscheme,rundescriptorhash,"
            + "consensussequence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", args);
        rows += args.size();
      }
      if (!cut.days().isEmpty()) {
        List<Object[]> args = new ArrayList<>(cut.days().size());
        cut.days().forEach((day, sides) -> args.add(new Object[] {scope, partition, java.sql.Date.valueOf(day), sides}));
        jdbc.batchUpdate("INSERT INTO projection_trade_days(projection_scope,partition_id,trade_date,sides) VALUES (?,?,?,?)"
            + " ON DUPLICATE KEY UPDATE sides=VALUES(sides)", args);
        rows += args.size();
      }
      jdbc.update("INSERT INTO projection_cursor(projection_scope,partition_id,stream_seq,consensus_seq,snapshot_at)"
          + " VALUES (?,?,?,?,NOW()) ON DUPLICATE KEY UPDATE stream_seq=VALUES(stream_seq),consensus_seq=VALUES(consensus_seq),"
          + "snapshot_at=VALUES(snapshot_at)", scope, partition, cut.streamSeq(), cut.consensusSeq());
      TradeView.Accounting a = cut.accounting();
      if (a != null && (a.sides() > 0 || a.duplicates() > 0)) {
        jdbc.update("INSERT INTO projection_accounting(projection_scope,partition_id,sides,max_seq,sum_seq,sum_sq,duplicates,epoch)"
            + " VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE sides=VALUES(sides),max_seq=VALUES(max_seq),sum_seq=VALUES(sum_seq),"
            + "sum_sq=VALUES(sum_sq),duplicates=VALUES(duplicates),epoch=VALUES(epoch)",
            scope, partition, a.sides(), a.maxSeq(), a.sumSeq(), a.sumSq(), a.duplicates(), a.epoch());
      }
      if (cut.applied() > 0) {
        jdbc.update("UPDATE projection_runs SET trade_rows=trade_rows+?, checkpoint_seq=GREATEST(checkpoint_seq,?) WHERE projection_scope=?",
            cut.applied(), cut.consensusSeq(), scope);
      }
      return rows;
    });
  }
}
