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
  public void persist(OrderUpdate update) {
    transactions.execute(status -> {
      lock.acquire();registry.validate(update);
      OrderRow row=OrderFeedHandler.toRow(update);
      OrderRow old=orders.findById(row.getId()).orElse(null);
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
      registry.requireWritable(update);
      if (row.getTraceId()==null && old!=null) {row.setTraceId(old.getTraceId());}
      orders.save(row);registry.applied(update,false);return null;
    });
  }
  private static boolean sameLegacyState(OrderRow old,OrderRow incoming) {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode a=mapper.valueToTree(old),b=mapper.valueToTree(incoming);
    for(String field:java.util.List.of("traceId","createdAt","updatedAt","eventDigest")) {a.remove(field);b.remove(field);}
    return a.equals(b);
  }
  static String digest(OrderUpdate update) {
    try {
      var mapper=JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
      var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(update);
      tree.remove("traceId"); // processing trace attribution is not economic event identity
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(tree)));
    } catch (Exception ex) {throw new IllegalArgumentException("invalid order event",ex);}
  }
}
