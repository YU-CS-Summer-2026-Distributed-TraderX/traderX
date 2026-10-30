package finos.traderx.tradeprocessor.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.tradeprocessor.model.OrderUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-DRS-30: the SQL side of the memory-only order view. Per partition, the view's OPEN orders (each as the update
 * the view holds, JSON) and the partition's order cursor, written in ONE transaction, so a restart loads them and
 * replays the stream from {@code cursor + 1} and holds exactly what it held. Without it the view rebuilt from the
 * last {@code replay-seconds} of the stream, and an order resting longer than that was gone after a restart.
 *
 * <p>Terminal orders are not kept: a dirty order that is no longer open is deleted. The view's terminal ring after
 * a restart is what the replay from the cursor brings back.
 *
 * <p>The cursor reuses {@code projection_cursor} under scope {@code order-view:<scope>} (the TradeSeqGaps pattern).
 * Writes delete by primary key only, so partitions never take each other's gap locks and need no shared lock row.
 */
@Service
public final class OrderViewSnapshotStore {
  public static final String CURSOR_PREFIX = "order-view:";
  static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public OrderViewSnapshotStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  /** The partition's committed order cursor, or -1 before its first snapshot. */
  public long loadCursor(String scope, int partition) {
    List<Long> rows = jdbc.query("SELECT stream_seq FROM projection_cursor WHERE projection_scope=? AND partition_id=?",
        (rs, i) -> rs.getLong(1), CURSOR_PREFIX + scope, partition);
    return rows.isEmpty() ? -1 : rows.get(0);
  }

  public List<OrderUpdate> load(String scope, int partition) {
    return jdbc.query("SELECT payload FROM order_view_snapshot WHERE projection_scope=? AND partition_id=?",
        (rs, i) -> {
          try { return JSON.readValue(rs.getString(1), OrderUpdate.class); }
          catch (Exception x) { throw new IllegalStateException("order_view_snapshot row unreadable", x); }
        }, scope, partition);
  }

  /** One snapshot: upsert {@code open}, delete {@code gone} (order ids), move the cursor. Returns rows written. */
  public int write(String scope, int partition, List<OrderUpdate> open, List<String> gone, long cursor) {
    return transactions.execute(status -> {
      if (!open.isEmpty()) {
        List<Object[]> args = new ArrayList<>(open.size());
        for (OrderUpdate u : open) {
          try {
            args.add(new Object[] {scope, partition, u.getId(), u.getAccountId(), u.getStatus(), JSON.writeValueAsString(u)});
          } catch (Exception x) { throw new IllegalStateException("order " + u.getId() + " not serializable", x); }
        }
        jdbc.batchUpdate("INSERT INTO order_view_snapshot(projection_scope,partition_id,orderid,accountid,status,payload)"
            + " VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE accountid=VALUES(accountid),status=VALUES(status),"
            + "payload=VALUES(payload)", args);
      }
      if (!gone.isEmpty()) {
        List<Object[]> args = new ArrayList<>(gone.size());
        for (String id : gone) { args.add(new Object[] {scope, partition, id}); }
        jdbc.batchUpdate("DELETE FROM order_view_snapshot WHERE projection_scope=? AND partition_id=? AND orderid=?", args);
      }
      jdbc.update("INSERT INTO projection_cursor(projection_scope,partition_id,stream_seq,consensus_seq,snapshot_at)"
          + " VALUES (?,?,?,0,NOW()) ON DUPLICATE KEY UPDATE stream_seq=VALUES(stream_seq),snapshot_at=VALUES(snapshot_at)",
          CURSOR_PREFIX + scope, partition, cursor);
      return open.size() + gone.size();
    });
  }

  /** Per partition: the committed order cursor and how many committed open orders are NEW or PARTIALLY_FILLED. */
  public Map<Integer, Map<String, Object>> committed(String scope) {
    Map<Integer, Map<String, Object>> out = new TreeMap<>();
    jdbc.query("SELECT partition_id, stream_seq FROM projection_cursor WHERE projection_scope=?",
        rs -> { out.put(rs.getInt(1), new java.util.LinkedHashMap<>(Map.of("cursor", rs.getLong(2), "open", 0L, "resting", 0L))); },
        CURSOR_PREFIX + scope);
    jdbc.query("SELECT partition_id, count(*), sum(status IN ('NEW','PARTIALLY_FILLED')) FROM order_view_snapshot"
        + " WHERE projection_scope=? GROUP BY partition_id", rs -> {
          Map<String, Object> m = out.computeIfAbsent(rs.getInt(1), k -> new java.util.LinkedHashMap<>(Map.of("cursor", -1L)));
          m.put("open", rs.getLong(2));
          m.put("resting", rs.getLong(3));
        }, scope);
    return out;
  }
}
