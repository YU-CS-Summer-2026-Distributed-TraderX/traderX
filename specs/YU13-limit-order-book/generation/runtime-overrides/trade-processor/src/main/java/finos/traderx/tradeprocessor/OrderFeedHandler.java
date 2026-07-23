package finos.traderx.tradeprocessor;

import finos.traderx.messaging.Envelope;
import finos.traderx.messaging.nats.NatsJSONSubscriber;
import finos.traderx.tradeprocessor.model.OrderRow;
import finos.traderx.tradeprocessor.model.OrderUpdate;
import finos.traderx.tradeprocessor.repository.OrderRepository;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Consumes order-lifecycle updates off the cluster's leader-side {@code /orders} bridge (YU13) and
 * upserts the {@code orderbook} read-model projection — the order-state sibling of
 * {@link TradeFeedHandler}. {@code onMessage} runs on the single NATS dispatcher thread, so it only
 * enqueues; a flush thread drains and persists, keeping the dispatcher unblocked exactly as the
 * trade path does.
 *
 * <p>Upsert by epoch-qualified id: an order goes NEW → PARTIALLY_FILLED → FILLED/CANCELED as updates
 * arrive, and {@code save()} on the fixed primary key overwrites the row to the latest state. NATS
 * preserves publish order, so the last write is the current state. At-least-once delivery is
 * idempotent — a replayed update rewrites the same row to the same value.
 *
 * <p><b>Rejection signal (brief 05 item 3):</b> this write path is not allowed to drop silently. A
 * row that fails to persist — the four known cases are VARCHAR truncation, a risk-gated /
 * synthetic account with no FK parent, and epoch-id collision — is counted in {@link #rejected} and
 * logged at WARN with the offending id, so a cluster-accepts / read-model-rejects divergence is
 * visible instead of a phantom missing order.
 */
public class OrderFeedHandler extends NatsJSONSubscriber<OrderUpdate> implements DisposableBean {
  static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderFeedHandler.class);

  private static final long MAX_LATENCY_MILLIS = 10;

  private final BlockingQueue<OrderUpdate> pending = new LinkedBlockingQueue<>();
  private final AtomicLong rejected = new AtomicLong();
  private volatile Thread flushThread;
  private volatile boolean running = true;

  public OrderFeedHandler() {
    super(OrderUpdate.class);
  }

  @Autowired
  private OrderRepository orderRepository;

  @Override
  public void afterPropertiesSet() throws Exception {
    super.afterPropertiesSet();
    flushThread = new Thread(this::flushLoop, "order-feed-flush");
    flushThread.setDaemon(true);
    flushThread.start();
  }

  @Override
  public void destroy() {
    running = false;
    if (flushThread != null) {
      flushThread.interrupt();
    }
  }

  @Override
  public void onMessage(Envelope<?> envelope, OrderUpdate update) {
    pending.add(update);
  }

  public long rejected() {
    return rejected.get();
  }

  private void flushLoop() {
    while (running) {
      try {
        OrderUpdate update = pending.poll(MAX_LATENCY_MILLIS, TimeUnit.MILLISECONDS);
        if (update != null) {
          persist(update);
        }
      } catch (InterruptedException x) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Package-private so the rejection signal is unit-testable with a throwing repository. */
  void persist(OrderUpdate update) {
    try {
      orderRepository.save(toRow(update));
    } catch (Exception x) {
      long n = rejected.incrementAndGet();
      log.warn("orderbook write rejected for order {} (rejected={}): {}",
          update.getId(), n, x.getMessage());
    }
  }

  static OrderRow toRow(OrderUpdate u) {
    OrderRow row = new OrderRow();
    row.setId(u.getId());
    row.setAccountId(u.getAccountId());
    row.setSecurity(u.getSecurity());
    row.setSide(u.getSide());
    row.setQuantity(u.getQuantity());
    row.setRemainingQuantity(u.getRemainingQuantity());
    row.setLimitPrice(u.getLimitPrice());
    row.setStatus(u.getStatus());
    row.setLastExecutionPrice(u.getLastExecutionPrice());
    row.setLastFillQuantity(u.getLastFillQuantity());
    row.setCreatedAt(u.getCreatedAt() == null ? new Date() : new Date(u.getCreatedAt()));
    row.setUpdatedAt(u.getUpdatedAt() == null ? new Date() : new Date(u.getUpdatedAt()));
    return row;
  }
}
