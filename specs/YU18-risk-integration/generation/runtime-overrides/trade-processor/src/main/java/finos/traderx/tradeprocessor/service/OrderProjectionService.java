package finos.traderx.tradeprocessor.service;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import finos.traderx.tradeprocessor.OrderFeedHandler;
import finos.traderx.tradeprocessor.OrderView;
import finos.traderx.tradeprocessor.model.*;
import finos.traderx.tradeprocessor.repository.OrderRepository;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class OrderProjectionService {
  private final OrderRepository orders; private final RunRegistry registry;
  private final ProjectionWriteLock lock; private final TransactionTemplate transactions;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  public OrderProjectionService(OrderRepository orders,RunRegistry registry,ProjectionWriteLock lock,
                                TransactionTemplate transactions) {
    this.orders=orders;this.registry=registry;this.lock=lock;this.transactions=transactions;
    this.jdbc=registry.jdbc();
  }
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
  private static final com.fasterxml.jackson.databind.ObjectMapper DIGEST_MAPPER =
      JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

  public void persist(OrderUpdate update) {
    transactions.execute(status -> {
      lock.acquire();
      apply(update,registry.scope(update.getProjectionScope()),
          orders.findById(update.getId()).orElse(null),null,null);
      return null;
    });
  }

  /**
   * YU18: a batch under ONE lock acquisition and ONE transaction - one run-row read per scope, one
   * findAllById, one counter UPDATE per scope - with exactly {@link #persist}'s per-update rules
   * applied in arrival order. Any refusal throws and rolls the whole batch back; the caller then
   * retries each update alone, so refusal semantics are per update as before.
   *
   * <p>FR-DRS-28: the rules run in memory against the latest row per order, and only each order's
   * final row is written, by {@link #upsert} in multi-row statements: one round trip per
   * {@link #UPSERT_ROWS} orders instead of one per order. The rows are the ones the per-update
   * path would have left, column for column.
   */
  public void persistAll(java.util.List<OrderUpdate> updates) {
    transactions.execute(status -> {
      lock.acquire();
      java.util.Map<String,java.util.Map<String,Object>> runs=new java.util.HashMap<>();
      java.util.Map<String,OrderRow> current=new java.util.HashMap<>();
      for (OrderRow r:orders.findAllById(updates.stream().map(OrderUpdate::getId).distinct().toList())) {current.put(r.getId(),r);}
      java.util.Map<String,long[]> applied=new java.util.HashMap<>();
      java.util.Map<String,OrderRow> written=new java.util.LinkedHashMap<>();
      for (OrderUpdate u:updates) {
        OrderRow saved=apply(u,runs.computeIfAbsent(u.getProjectionScope(),registry::scope),current.get(u.getId()),applied,written);
        if (saved!=null) {current.put(saved.getId(),saved);}
      }
      // Rows before the shared counter row's UPDATE, for the reason in TradeService.processTrades.
      upsert(written.values());
      for (var a:applied.entrySet()) {registry.appliedBatch(a.getKey(),false,a.getValue()[0],a.getValue()[1]);}
      return null;
    });
  }

  /** Orders per upsert statement: 500 x 35 parameters stays under the protocol's 65,535. */
  static final int UPSERT_ROWS = 500;
  private static final String[] COLUMNS = {"orderid","accountid","security","side","quantity","remainingquantity",
      "limitprice","status","createdat","updatedat","lastexecutionprice","lastfillquantity","filledquantity","traceid",
      "clientorderkey","ordertype","timeinforce","stopprice","displayquantity","pegreference","pegoffset","pegcap",
      "trailamount","trailpercentbps","triggered","sessiondate","suspendreason","reason","PROJECTIONSCOPE",
      "CLUSTEREPOCH","EVENTIDSCHEME","RUNDESCRIPTORHASH","CONSENSUSSEQUENCE","OUTPUTORDINAL","EVENTDIGEST"};

  /** The final rows of a batch, every mapped column, in statements of up to {@link #UPSERT_ROWS}. */
  private void upsert(java.util.Collection<OrderRow> rows) {
    if (rows.isEmpty()) {return;}
    java.util.List<OrderRow> all=new java.util.ArrayList<>(rows);
    jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) c -> {
      for (int from=0;from<all.size();from+=UPSERT_ROWS) {
        java.util.List<OrderRow> chunk=all.subList(from,Math.min(all.size(),from+UPSERT_ROWS));
        try (var ps=c.prepareStatement(upsertSql(chunk.size()))) {
          int i=1;
          for (OrderRow r:chunk) {i=bind(ps,i,r);}
          ps.executeUpdate();
        }
      }
      return null;
    });
  }

  static String upsertSql(int rows) {
    String one="("+String.join(",",java.util.Collections.nCopies(COLUMNS.length,"?"))+")";
    StringBuilder sql=new StringBuilder("INSERT INTO orderbook(").append(String.join(",",COLUMNS)).append(") VALUES ")
        .append(String.join(",",java.util.Collections.nCopies(rows,one))).append(" ON DUPLICATE KEY UPDATE ");
    for (int c=1;c<COLUMNS.length;c++) {sql.append(c>1?",":"").append(COLUMNS[c]).append("=VALUES(").append(COLUMNS[c]).append(')');}
    return sql.toString();
  }

  /** Binds one row in {@link #COLUMNS} order, with a typed NULL where the value is absent. */
  private static int bind(java.sql.PreparedStatement ps,int i,OrderRow r) throws java.sql.SQLException {
    str(ps,i++,r.getId());num(ps,i++,r.getAccountId());str(ps,i++,r.getSecurity());str(ps,i++,r.getSide());
    num(ps,i++,r.getQuantity());num(ps,i++,r.getRemainingQuantity());dec(ps,i++,r.getLimitPrice());str(ps,i++,r.getStatus());
    time(ps,i++,r.getCreatedAt());time(ps,i++,r.getUpdatedAt());dec(ps,i++,r.getLastExecutionPrice());
    num(ps,i++,r.getLastFillQuantity());num(ps,i++,r.getFilledQuantity());str(ps,i++,r.getTraceId());
    str(ps,i++,r.getClientOrderKey());str(ps,i++,r.getOrderType());str(ps,i++,r.getTimeInForce());dec(ps,i++,r.getStopPrice());
    num(ps,i++,r.getDisplayQuantity());str(ps,i++,r.getPegReference());num(ps,i++,r.getPegOffset());dec(ps,i++,r.getPegCap());
    dec(ps,i++,r.getTrailAmount());num(ps,i++,r.getTrailPercentBps());
    if (r.getTriggered()==null) {ps.setNull(i++,java.sql.Types.BOOLEAN);} else {ps.setBoolean(i++,r.getTriggered());}
    num(ps,i++,r.getSessionDate());str(ps,i++,r.getSuspendReason());str(ps,i++,r.getReason());
    str(ps,i++,r.getProjectionScope());str(ps,i++,r.getClusterEpoch());str(ps,i++,r.getEventIdScheme());
    str(ps,i++,r.getRunDescriptorHash());
    if (r.getConsensusSequence()==null) {ps.setNull(i++,java.sql.Types.BIGINT);} else {ps.setLong(i++,r.getConsensusSequence());}
    num(ps,i++,r.getOutputOrdinal());str(ps,i++,r.getEventDigest());
    return i;
  }
  private static void str(java.sql.PreparedStatement ps,int i,String v) throws java.sql.SQLException {
    if (v==null) {ps.setNull(i,java.sql.Types.VARCHAR);} else {ps.setString(i,v);}
  }
  private static void num(java.sql.PreparedStatement ps,int i,Integer v) throws java.sql.SQLException {
    if (v==null) {ps.setNull(i,java.sql.Types.INTEGER);} else {ps.setInt(i,v);}
  }
  private static void dec(java.sql.PreparedStatement ps,int i,java.math.BigDecimal v) throws java.sql.SQLException {
    if (v==null) {ps.setNull(i,java.sql.Types.DECIMAL);} else {ps.setBigDecimal(i,v);}
  }
  private static void time(java.sql.PreparedStatement ps,int i,java.util.Date v) throws java.sql.SQLException {
    if (v==null) {ps.setNull(i,java.sql.Types.TIMESTAMP);} else {ps.setTimestamp(i,new java.sql.Timestamp(v.getTime()));}
  }

  /** One update's rules against the current row; returns the written row, or null when nothing was
   *  written. {@code applied} null = count this row immediately (single-update path); {@code written}
   *  non-null = collect the row for the batch's upsert instead of writing it through JPA. */
  private OrderRow apply(OrderUpdate update,java.util.Map<String,Object> run,OrderRow old,
                         java.util.Map<String,long[]> applied,java.util.Map<String,OrderRow> written) {
    {
      registry.validate(update,run);
      OrderRow row=OrderFeedHandler.toRow(update);
      // FR-DRS-27: from this row's own previous state, never the in-memory view's (it may not hold it).
      Integer before=old==null ? Integer.valueOf(0) : old.getFilledQuantity()!=null ? old.getFilledQuantity()
          : OrderView.filled(old.getStatus(),old.getQuantity(),old.getRemainingQuantity(),null); // a row from before the column
      row.setFilledQuantity(OrderView.filled(row.getStatus(),row.getQuantity(),row.getRemainingQuantity(),before));
      if (old!=null && !Objects.equals(old.getProjectionScope(),row.getProjectionScope())) {
        throw new IllegalArgumentException("ORDER_SCOPE_CONFLICT: "+row.getId());
      }
      if (!RunRegistry.UNKNOWN.equals(row.getProjectionScope())) {
        row.setEventDigest(digest(update));
        if (old!=null) {
          if (!Objects.equals(old.getRunDescriptorHash(),row.getRunDescriptorHash())
              || !Objects.equals(old.getAccountId(),row.getAccountId())
              || !Objects.equals(old.getSecurity(),row.getSecurity())
              || !Objects.equals(old.getSide(),row.getSide())) {
            throw new IllegalArgumentException("ORDER_PROVENANCE_CONFLICT: "+row.getId());
          }
          int order=Long.compare(row.getConsensusSequence(),old.getConsensusSequence());
          if (order==0) {order=Integer.compare(row.getOutputOrdinal(),old.getOutputOrdinal());}
          if (order<0) {return null;} // Late scoped update cannot rewind a current order.
          if (order==0) {
            if (!Objects.equals(old.getEventDigest(),row.getEventDigest())) {
              throw new IllegalArgumentException("ORDER_EVENT_CONFLICT: "+row.getId());
            }
            return null;
          }
        }
      }
      // FR-DRS-28: the run row the caller read for this scope in this transaction, not a fresh SELECT per
      // update: every update of an unattributed (legacy-unknown) run used to pay one round trip here.
      if(old!=null && RunRegistry.UNKNOWN.equals(row.getProjectionScope())
          && "SEALED".equals(run.get("phase")) && sameLegacyState(old,row)) return null;
      registry.requireWritable(update,run);
      if (row.getTraceId()==null && old!=null) {row.setTraceId(old.getTraceId());}
      if (row.getClientOrderKey()==null && old!=null) {row.setClientOrderKey(old.getClientOrderKey());}   // ADR-093
      // persist() for a row known to be absent skips merge()'s SELECT; an existing row is already
      // managed in this transaction, so merging onto it issues no read either.
      OrderRow saved;
      if (written!=null) {written.put(row.getId(),row);saved=row;}
      else if (old==null && entityManager!=null) {entityManager.persist(row);saved=row;}
      else {saved=orders.save(row);}
      if (applied==null) {registry.applied(update,false);}
      else if (update.getConsensusSequence()!=null && !RunRegistry.UNKNOWN.equals(update.getProjectionScope())) {
        long[] a=applied.computeIfAbsent(update.getProjectionScope(),k->new long[2]);
        a[0]++;a[1]=Math.max(a[1],update.getConsensusSequence());
      }
      return saved;
    }
  }
  private static boolean sameLegacyState(OrderRow old,OrderRow incoming) {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode a=mapper.valueToTree(old),b=mapper.valueToTree(incoming);
    for(String field:java.util.List.of("traceId","clientOrderKey","createdAt","updatedAt","eventDigest","filledQuantity")) {a.remove(field);b.remove(field);}
    return a.equals(b);
  }
  static String digest(OrderUpdate update) {
    try {
      var mapper=DIGEST_MAPPER; // was built per update
      var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(update);
      tree.remove("traceId"); // processing trace attribution is not economic event identity
      // ADR-093: nor is the client's key. Leaving it in would change the digest of every event retained before the
      // field existed (the bean serializes it, as null), and recovery would refuse that history.
      tree.remove("clientOrderKey");
      tree.remove("filledQuantity"); // derived by the read model (FR-DRS-27), not part of the event
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(tree)));
    } catch (Exception ex) {throw new IllegalArgumentException("invalid order event",ex);}
  }
}
