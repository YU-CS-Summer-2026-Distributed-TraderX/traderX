package finos.traderx.tradeprocessor;

import finos.traderx.messaging.Envelope;
import finos.traderx.messaging.nats.NatsJSONSubscriber;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.service.TradeService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Consumes booked trades off NATS and hands them to {@link TradeService} in batches rather than
 * one DB transaction per trade. Under load, each trade previously cost its own round trip to
 * MariaDB (SELECT position + INSERT trade + UPDATE position); batching collapses that to a
 * handful of round trips per batch via {@code saveAll()} + Hibernate JDBC batching (see
 * {@code spring.jpa.properties.hibernate.jdbc.batch_size} in application.properties).
 *
 * <p>{@code onMessage} is called on the single NATS dispatcher thread, so it never blocks on the
 * queue — it just offers and returns. A dedicated flush thread drains the queue: eagerly once
 * {@link #BATCH_SIZE} trades are queued, or at worst every {@link #MAX_LATENCY_MILLIS} so trades
 * never sit indefinitely under low load.
 */
public class TradeFeedHandler extends NatsJSONSubscriber<TradeOrder> implements DisposableBean {
  static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TradeFeedHandler.class);

  private static final int BATCH_SIZE = 100;
  private static final long MAX_LATENCY_MILLIS = 10;

  private final BlockingQueue<TradeOrder> pending = new LinkedBlockingQueue<>();
  private volatile Thread flushThread;
  private volatile boolean running = true;

  public TradeFeedHandler() {
    super(TradeOrder.class);
  }

  @Autowired
  private TradeService tradeService;

  @Override
  public void afterPropertiesSet() throws Exception {
    super.afterPropertiesSet();
    flushThread = new Thread(this::flushLoop, "trade-feed-batch-flush");
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
  public void onMessage(Envelope<?> envelope, TradeOrder order) {
    pending.add(order);
  }

  private void flushLoop() {
    List<TradeOrder> batch = new ArrayList<>(BATCH_SIZE);
    while (running) {
      try {
        TradeOrder first = pending.poll(MAX_LATENCY_MILLIS, TimeUnit.MILLISECONDS);
        if (first == null) {
          continue;
        }
        batch.add(first);
        pending.drainTo(batch, BATCH_SIZE - 1);
        processBatch(batch);
        batch.clear();
      } catch (InterruptedException x) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void processBatch(List<TradeOrder> batch) {
    try {
      tradeService.processTrades(batch);
    } catch (Exception x) {
      log.error("Batch trade processing failed for {} trades; retrying individually", batch.size(), x);
      // Fall back to per-trade processing so one bad order doesn't silently drop the whole batch.
      for (TradeOrder order : batch) {
        try {
          tradeService.processTrade(order);
        } catch (Exception individualFailure) {
          log.error("Error processing trade order {}", order, individualFailure);
        }
      }
    }
  }
}
