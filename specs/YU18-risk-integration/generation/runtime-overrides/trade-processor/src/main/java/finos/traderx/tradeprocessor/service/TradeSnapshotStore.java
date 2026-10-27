package finos.traderx.tradeprocessor.service;

import finos.traderx.tradeprocessor.TradeView;
import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeSide;
import finos.traderx.tradeprocessor.model.TradeState;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * YU18 trade store (docs/engineering/downstream-at-400k.md §7): the SQL side of {@link TradeView}.
 * One transaction per snapshot: the changed positions (multi-row upsert), each changed account's
 * blotter window (insert the window's rows, delete the account's rows outside it), the stream
 * cursor, and the scope's counters. The cursor row is what makes a restart exact: the view reloads
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

  /** The blotter windows, oldest first, so {@link TradeView#load} re-fills each ring in order. */
  public List<Trade> loadBlotters(String scope) {
    return jdbc.query("SELECT id, accountid, security, side, state, quantity, price, updated, created, settlementdate, "
        + "rejectionreason, sourceorderid, projectionscope, clusterepoch, eventidscheme, rundescriptorhash, consensussequence "
        + "FROM trades WHERE projectionscope=? ORDER BY consensussequence, created, id",
        (rs, i) -> {
          Trade t = new Trade();
          t.setId(rs.getString(1));
          t.setAccountId(rs.getInt(2));
          t.setSecurity(rs.getString(3));
          t.setSide(rs.getString(4) == null ? null : TradeSide.valueOf(rs.getString(4)));
          t.setState(rs.getString(5) == null ? null : TradeState.valueOf(rs.getString(5)));
          t.setQuantity(rs.getInt(6));
          t.setPrice(rs.getBigDecimal(7));
          t.setUpdated(rs.getTimestamp(8));
          t.setCreated(rs.getTimestamp(9));
          t.setSettlementDate(rs.getTimestamp(10));
          t.setRejectionReason(rs.getString(11));
          t.setSourceOrderId(rs.getString(12));
          t.setProjectionScope(rs.getString(13));
          t.setClusterEpoch(rs.getString(14));
          t.setEventIdScheme(rs.getString(15));
          t.setRunDescriptorHash(rs.getString(16));
          long seq = rs.getLong(17);
          t.setConsensusSequence(rs.wasNull() ? null : seq);
          return t;
        }, scope);
  }

  /** Write one cut atomically. Returns the number of SQL rows written. */
  public int write(String scope, int partition, TradeView.Cut cut) {
    return transactions.execute(status -> {
      ProjectionWriteLock.partition(partition);
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
      for (Map.Entry<Integer, List<Trade>> e : cut.blotters().entrySet()) {
        List<Trade> window = e.getValue();
        if (window.isEmpty()) { continue; }
        List<Object[]> args = new ArrayList<>(window.size());
        StringBuilder ids = new StringBuilder();
        List<Object> delArgs = new ArrayList<>();
        delArgs.add(window.get(0).getProjectionScope());
        delArgs.add(e.getKey());
        for (Trade t : window) {
          args.add(new Object[] {t.getId(), t.getAccountId(), t.getSecurity(), t.getSide() == null ? null : t.getSide().name(),
              t.getState() == null ? null : t.getState().name(), t.getQuantity(), t.getPrice(),
              t.getUpdated() == null ? null : new Timestamp(t.getUpdated().getTime()),
              t.getCreated() == null ? null : new Timestamp(t.getCreated().getTime()),
              t.getSettlementDate() == null ? null : new Timestamp(t.getSettlementDate().getTime()),
              t.getRejectionReason(), t.getSourceOrderId(), t.getProjectionScope(), t.getClusterEpoch(),
              t.getEventIdScheme(), t.getRunDescriptorHash(), t.getConsensusSequence()});
          ids.append(ids.length() == 0 ? "?" : ",?");
          delArgs.add(t.getId());
        }
        jdbc.batchUpdate("INSERT IGNORE INTO trades(id,accountid,security,side,state,quantity,price,updated,created,"
            + "settlementdate,rejectionreason,sourceorderid,projectionscope,clusterepoch,eventidscheme,rundescriptorhash,"
            + "consensussequence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", args);
        jdbc.update("DELETE FROM trades WHERE projectionscope=? AND accountid=? AND id NOT IN (" + ids + ")", delArgs.toArray());
        rows += args.size();
      }
      jdbc.update("INSERT INTO projection_cursor(projection_scope,partition_id,stream_seq,consensus_seq,snapshot_at)"
          + " VALUES (?,?,?,?,NOW()) ON DUPLICATE KEY UPDATE stream_seq=VALUES(stream_seq),consensus_seq=VALUES(consensus_seq),"
          + "snapshot_at=VALUES(snapshot_at)", scope, partition, cut.streamSeq(), cut.consensusSeq());
      if (cut.applied() > 0) {
        jdbc.update("UPDATE projection_runs SET trade_rows=trade_rows+?, checkpoint_seq=GREATEST(checkpoint_seq,?) WHERE projection_scope=?",
            cut.applied(), cut.consensusSeq(), scope);
      }
      return rows;
    });
  }
}
