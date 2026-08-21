package finos.traderx.algoengine.eventstore;

import finos.traderx.algoengine.model.Bucket;
import finos.traderx.algoengine.model.ParentOrder;
import finos.traderx.algoengine.model.ParentOrderStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  /** Parent ids named by an event that this state holds no parent for. Distinct ids, not a count
   * of events: what an operator acts on is how many parents are unowned, not how many messages
   * mentioned them. See {@link #parent}. */
  private final Set<String> orphanedParents = ConcurrentHashMap.newKeySet();

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
    ParentOrder order = parent(event.getParentOrderId());
    if (order != null) {
      order.setStatus(ParentOrderStatus.COMPLETED);
    }
  }

  private Bucket bucket(AlgoEvent event) {
    ParentOrder order = parent(event.getParentOrderId());
    if (order == null || event.getBucketIndex() == null) {
      return null;
    }
    int index = event.getBucketIndex();
    if (index < 0 || index >= order.getBuckets().size()) {
      return null;
    }
    return order.getBuckets().get(index);
  }

  /**
   * Looks a parent up and records the miss. Every event type that refers to an already-created
   * parent routes through here, so the record is taken once in one place rather than per caller.
   *
   * <p>A miss is a tear in the log: the only way an event can name a parent this state never
   * created is that the event's {@code ParentOrderCreated} is not in the log being applied. During
   * a replay that is the ONLY in-process evidence of the tear, because a wipe resets the stream's
   * sequencing and leaves the surviving tail arithmetically identical to a complete log. Dropping
   * it on the floor is how a torn log replays clean while its children rest in the book with no
   * parent (measured on the kind rig 2026-08-21). Deliberately not logged here — a per-event line
   * would be noise; {@link #orphanedParents()} lets the replay report it once, as a verdict.
   */
  private ParentOrder parent(String parentOrderId) {
    ParentOrder order = orders.get(parentOrderId);
    if (order == null) {
      orphanedParents.add(parentOrderId);
    }
    return order;
  }

  /** Snapshot of the parent ids events have named but that no {@code ParentOrderCreated} in the
   * applied log ever created. Grows over the life of this state; a caller that wants a window
   * takes a snapshot on each side of it and diffs. */
  public Set<String> orphanedParents() {
    return Set.copyOf(orphanedParents);
  }

  public ParentOrder get(String parentOrderId) {
    return orders.get(parentOrderId);
  }

  public Collection<ParentOrder> all() {
    return orders.values();
  }
}
