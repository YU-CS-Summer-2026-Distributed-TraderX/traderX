package finos.traderx.algoengine.service;

import finos.traderx.algoengine.eventstore.AlgoEvent;
import finos.traderx.algoengine.eventstore.AlgoEventStore;
import finos.traderx.algoengine.eventstore.AlgoOrderState;
import finos.traderx.algoengine.model.AlgoType;
import finos.traderx.algoengine.model.Bucket;
import finos.traderx.algoengine.model.OrderSide;
import finos.traderx.algoengine.model.ParentOrder;
import finos.traderx.algoengine.orders.OrderMatcherClient;
import finos.traderx.algoengine.orders.PriceClient;
import finos.traderx.algoengine.schedule.TwapScheduleBuilder;
import finos.traderx.algoengine.schedule.VwapScheduleBuilder;
import finos.traderx.algoengine.volume.VolumeProfileSource;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestrates parent-order creation, TWAP/VWAP scheduling, child-order submission, and fill
 * tracking. Every mutation to {@link AlgoOrderState} goes through {@link AlgoEventStore#append},
 * applied only after the event is durably appended (ADR-030) — this class never mutates the state
 * projection directly.
 */
@Service
public class AlgoOrderService {
  private static final Logger log = LoggerFactory.getLogger(AlgoOrderService.class);

  private final AlgoEventStore eventStore;
  private final AlgoOrderState state = new AlgoOrderState();
  private final PriceClient priceClient;
  private final OrderMatcherClient orderMatcherClient;
  private final VolumeProfileSource volumeProfileSource;
  private final int defaultBucketSeconds;
  private final BigDecimal limitOffsetFraction;

  /** childOrderId -> (parentOrderId, bucketIndex), rebuilt from ChildOrderSubmitted events on
   * replay as well as populated live — lets fill events (which only carry orderId) be routed back
   * to the owning parent/bucket without touching order-matcher's output payload. */
  private final Map<String, ChildRef> childIndex = new ConcurrentHashMap<>();

  private record ChildRef(String parentOrderId, int bucketIndex) {}

  public AlgoOrderService(
      AlgoEventStore eventStore,
      PriceClient priceClient,
      OrderMatcherClient orderMatcherClient,
      VolumeProfileSource volumeProfileSource,
      @Value("${algo.bucket-seconds-default:10}") int defaultBucketSeconds,
      @Value("${algo.limit-offset-bps:10}") int limitOffsetBps) {
    this.eventStore = eventStore;
    this.priceClient = priceClient;
    this.orderMatcherClient = orderMatcherClient;
    this.volumeProfileSource = volumeProfileSource;
    this.defaultBucketSeconds = defaultBucketSeconds;
    this.limitOffsetFraction = BigDecimal.valueOf(limitOffsetBps).divide(BigDecimal.valueOf(10_000));
  }

  @PostConstruct
  void start() throws Exception {
    eventStore.replayAndSubscribe(this::applyAndIndex);
  }

  private void applyAndIndex(AlgoEvent event) {
    state.apply(event);
    if (event.getType() == AlgoEvent.Type.ChildOrderSubmitted) {
      childIndex.put(event.getChildOrderId(), new ChildRef(event.getParentOrderId(), event.getBucketIndex()));
    }
  }

  public ParentOrder create(Integer accountId, String security, OrderSide side, int quantity,
      AlgoType algoType, int durationSeconds, Integer bucketSecondsOverride) throws Exception {
    int bucketSeconds = bucketSecondsOverride != null ? bucketSecondsOverride : defaultBucketSeconds;
    String parentOrderId = UUID.randomUUID().toString();
    long now = Instant.now().toEpochMilli();

    List<Bucket> buckets = algoType == AlgoType.TWAP
        ? TwapScheduleBuilder.build(quantity, durationSeconds, bucketSeconds, now)
        : VwapScheduleBuilder.build(quantity,
            volumeProfileSource.bucketWeights(security, bucketCount(durationSeconds, bucketSeconds)),
            bucketSeconds, now);

    List<AlgoEvent.BucketSeed> seeds = new ArrayList<>();
    for (Bucket b : buckets) {
      seeds.add(new AlgoEvent.BucketSeed(b.getIndex(), b.getStartEpochMs(), b.getTargetQuantity()));
    }

    AlgoEvent created = AlgoEvent.parentOrderCreated(parentOrderId, accountId, security, side,
        quantity, algoType, durationSeconds, bucketSeconds, seeds, Instant.now());
    eventStore.append(created);
    applyAndIndex(created);
    return state.get(parentOrderId);
  }

  private static int bucketCount(int durationSeconds, int bucketSeconds) {
    return Math.max(1, (int) Math.ceil((double) durationSeconds / bucketSeconds));
  }

  public ParentOrder get(String parentOrderId) {
    return state.get(parentOrderId);
  }

  public Collection<ParentOrder> all() {
    return state.all();
  }

  /** Called by {@code AlgoScheduler} on every tick: submits every due, not-yet-submitted bucket
   * across every running parent order. A submission failure (price fetch or order-matcher call)
   * is logged and left for the next tick — the bucket is only marked submitted after a successful
   * response, so nothing is double-submitted. */
  public void submitDueBuckets(long nowEpochMs) {
    for (ParentOrder order : state.all()) {
      if (order.getStatus() != finos.traderx.algoengine.model.ParentOrderStatus.RUNNING) {
        continue;
      }
      for (Bucket bucket : order.getBuckets()) {
        if (bucket.isSubmitted() || bucket.getStartEpochMs() > nowEpochMs) {
          continue;
        }
        submitBucket(order, bucket);
      }
    }
  }

  private void submitBucket(ParentOrder order, Bucket bucket) {
    String clientOrderId = order.getParentOrderId() + ":" + bucket.getIndex();
    try {
      BigDecimal reference = priceClient.currentPrice(order.getSecurity());
      BigDecimal offset = order.getSide() == OrderSide.Buy
          ? BigDecimal.ONE.add(limitOffsetFraction)
          : BigDecimal.ONE.subtract(limitOffsetFraction);
      BigDecimal limitPrice = reference.multiply(offset).setScale(2, RoundingMode.HALF_UP);

      String childOrderId = orderMatcherClient.submitChildOrder(clientOrderId, order.getAccountId(),
          order.getSecurity(), order.getSide(), bucket.getTargetQuantity(), limitPrice);

      AlgoEvent submitted = AlgoEvent.childOrderSubmitted(order.getParentOrderId(), bucket.getIndex(),
          childOrderId, clientOrderId, limitPrice, Instant.now());
      eventStore.append(submitted);
      applyAndIndex(submitted);
    } catch (Exception ex) {
      log.warn("child order submission failed for {} (will retry next tick): {}", clientOrderId, ex.toString());
    }
  }

  /** Called by {@code OrderUpdateSubscriber} for every {@code /accounts/*}{@code /orders} event
   * whose {@code orderId} matches a known child order. */
  public void onOrderUpdate(String orderId, Integer remainingQuantity, BigDecimal lastExecutionPrice) {
    ChildRef ref = childIndex.get(orderId);
    if (ref == null) {
      return; // not a child order this engine submitted
    }
    try {
      AlgoEvent fillObserved = AlgoEvent.childOrderFillObserved(ref.parentOrderId(), ref.bucketIndex(),
          remainingQuantity, lastExecutionPrice, Instant.now());
      eventStore.append(fillObserved);
      applyAndIndex(fillObserved);

      ParentOrder order = state.get(ref.parentOrderId());
      if (order != null && order.getStatus() == finos.traderx.algoengine.model.ParentOrderStatus.RUNNING
          && order.getBuckets().stream().allMatch(Bucket::isSubmitted)
          && order.allBucketsFilled()) {
        AlgoEvent completed = AlgoEvent.parentOrderCompleted(ref.parentOrderId(), Instant.now());
        eventStore.append(completed);
        applyAndIndex(completed);
      }
    } catch (Exception ex) {
      log.warn("failed to record fill for order {}: {}", orderId, ex.toString());
    }
  }
}
