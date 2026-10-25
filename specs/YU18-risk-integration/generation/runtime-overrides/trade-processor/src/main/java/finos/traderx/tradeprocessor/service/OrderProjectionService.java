package finos.traderx.tradeprocessor.service;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import finos.traderx.tradeprocessor.OrderFeedHandler;
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
  public OrderProjectionService(OrderRepository orders,RunRegistry registry,ProjectionWriteLock lock,
                                TransactionTemplate transactions) {
    this.orders=orders;this.registry=registry;this.lock=lock;this.transactions=transactions;
  }
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
  private static final com.fasterxml.jackson.databind.ObjectMapper DIGEST_MAPPER =
      JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

  public void persist(OrderUpdate update) {
    transactions.execute(status -> {
      lock.acquire();
      apply(update,registry.scope(update.getProjectionScope()),
          orders.findById(update.getId()).orElse(null),null);
      return null;
    });
  }

  /**
   * YU18: a batch under ONE lock acquisition and ONE transaction - one run-row read per scope, one
   * findAllById, one counter UPDATE per scope - with exactly {@link #persist}'s per-update rules
   * applied in arrival order. Any refusal throws and rolls the whole batch back; the caller then
   * retries each update alone, so refusal semantics are per update as before.
   */
  public void persistAll(java.util.List<OrderUpdate> updates) {
    transactions.execute(status -> {
      lock.acquire();
      java.util.Map<String,java.util.Map<String,Object>> runs=new java.util.HashMap<>();
      java.util.Map<String,OrderRow> current=new java.util.HashMap<>();
      for (OrderRow r:orders.findAllById(updates.stream().map(OrderUpdate::getId).distinct().toList())) {current.put(r.getId(),r);}
      java.util.Map<String,long[]> applied=new java.util.HashMap<>();
      for (OrderUpdate u:updates) {
        OrderRow saved=apply(u,runs.computeIfAbsent(u.getProjectionScope(),registry::scope),current.get(u.getId()),applied);
        if (saved!=null) {current.put(saved.getId(),saved);}
      }
      for (var a:applied.entrySet()) {registry.appliedBatch(a.getKey(),false,a.getValue()[0],a.getValue()[1]);}
      return null;
    });
  }

  /** One update's rules against the current row; returns the written row, or null when nothing was
   *  written. {@code applied} null = count this row immediately (single-update path). */
  private OrderRow apply(OrderUpdate update,java.util.Map<String,Object> run,OrderRow old,
                         java.util.Map<String,long[]> applied) {
    {
      registry.validate(update,run);
      OrderRow row=OrderFeedHandler.toRow(update);
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
      if(old!=null && RunRegistry.UNKNOWN.equals(row.getProjectionScope())
          && "SEALED".equals(registry.scope(row.getProjectionScope()).get("phase")) && sameLegacyState(old,row)) return null;
      registry.requireWritable(update,run);
      if (row.getTraceId()==null && old!=null) {row.setTraceId(old.getTraceId());}
      // persist() for a row known to be absent skips merge()'s SELECT; an existing row is already
      // managed in this transaction, so merging onto it issues no read either.
      OrderRow written;
      if (old==null && entityManager!=null) {entityManager.persist(row);written=row;}
      else {written=orders.save(row);}
      if (applied==null) {registry.applied(update,false);}
      else if (update.getConsensusSequence()!=null) {
        long[] a=applied.computeIfAbsent(update.getProjectionScope(),k->new long[2]);
        a[0]++;a[1]=Math.max(a[1],update.getConsensusSequence());
      }
      return written;
    }
  }
  private static boolean sameLegacyState(OrderRow old,OrderRow incoming) {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode a=mapper.valueToTree(old),b=mapper.valueToTree(incoming);
    for(String field:java.util.List.of("traceId","createdAt","updatedAt","eventDigest")) {a.remove(field);b.remove(field);}
    return a.equals(b);
  }
  static String digest(OrderUpdate update) {
    try {
      var mapper=DIGEST_MAPPER; // was built per update
      var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(update);
      tree.remove("traceId"); // processing trace attribution is not economic event identity
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(tree)));
    } catch (Exception ex) {throw new IllegalArgumentException("invalid order event",ex);}
  }
}
