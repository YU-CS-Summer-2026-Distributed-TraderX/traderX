package finos.traderx.algoengine.eventstore;

import finos.traderx.algoengine.model.Bucket;
import finos.traderx.algoengine.model.ParentOrder;
import finos.traderx.algoengine.model.ParentOrderStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure in-memory projection of the {@code TRADERX_ALGO_ENGINE} event log (ADR-030). No I/O — every
 * {@link #apply(AlgoEvent)} call is a deterministic function of the event and current state, which
 * is what makes replaying the whole stream on boot reproduce the exact pre-crash state. Applying an
 * event twice (JetStream redelivery after a crash between append and ack) is safe: each event fully
 * replaces the fields it carries rather than incrementing a counter.
 */
public class AlgoOrderState {
  private final Map<String, ParentOrder> orders = new ConcurrentHashMap<>();

  public void apply(AlgoEvent event) {
    switch (event.getType()) {
      case ParentOrderCreated -> applyCreated(event);
      case ChildOrderSubmitted -> applySubmitted(event);
      case ChildOrderFillObserved -> applyFillObserved(event);
      case ParentOrderCompleted -> applyCompleted(event);
    }
  }

  private void applyCreated(AlgoEvent event) {
    ParentOrder order = new ParentOrder();
    order.setParentOrderId(event.getParentOrderId());
    order.setAccountId(event.getAccountId());
    order.setSecurity(event.getSecurity());
    order.setSide(event.getSide());
    order.setQuantity(event.getQuantity());
    order.setAlgoType(event.getAlgoType());
    order.setDurationSeconds(event.getDurationSeconds());
    order.setBucketSeconds(event.getBucketSeconds());
    order.setCreatedAt(event.getOccurredAt());
    order.setStatus(ParentOrderStatus.RUNNING);
    List<Bucket> buckets = new ArrayList<>();
    for (AlgoEvent.BucketSeed seed : event.getBuckets()) {
      buckets.add(new Bucket(seed.index, seed.startEpochMs, seed.targetQuantity));
    }
    order.setBuckets(buckets);
    orders.put(order.getParentOrderId(), order);
  }

  private void applySubmitted(AlgoEvent event) {
    Bucket bucket = bucket(event);
    if (bucket == null) {
      return;
    }
    bucket.setChildOrderId(event.getChildOrderId());
    bucket.setClientOrderId(event.getClientOrderId());
    bucket.setLimitPrice(event.getLimitPrice());
    bucket.setSubmittedAt(event.getOccurredAt());
  }

  private void applyFillObserved(AlgoEvent event) {
    Bucket bucket = bucket(event);
    if (bucket == null) {
      return;
    }
    bucket.setRemainingQuantity(event.getRemainingQuantity());
    bucket.setLastExecutionPrice(event.getLastExecutionPrice());
    if (event.getRemainingQuantity() != null && event.getRemainingQuantity() == 0) {
      bucket.setFilled(true);
    }
  }

  private void applyCompleted(AlgoEvent event) {
    ParentOrder order = orders.get(event.getParentOrderId());
    if (order != null) {
      order.setStatus(ParentOrderStatus.COMPLETED);
    }
  }

  private Bucket bucket(AlgoEvent event) {
    ParentOrder order = orders.get(event.getParentOrderId());
    if (order == null || event.getBucketIndex() == null) {
      return null;
    }
    int index = event.getBucketIndex();
    if (index < 0 || index >= order.getBuckets().size()) {
      return null;
    }
    return order.getBuckets().get(index);
  }

  public ParentOrder get(String parentOrderId) {
    return orders.get(parentOrderId);
  }

  public Collection<ParentOrder> all() {
    return orders.values();
  }
}
