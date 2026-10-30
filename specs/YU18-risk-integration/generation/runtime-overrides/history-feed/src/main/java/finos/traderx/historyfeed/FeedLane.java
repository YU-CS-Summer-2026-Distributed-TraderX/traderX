package finos.traderx.historyfeed;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamOptions;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One (feed, partition) of the history feed (FR-HST-02/03): its stream's subject into its table.
 *
 * <p>The store is the cursor. It holds exactly this subject's messages up to max(seq), written in stream order, so a
 * lane (re)creates its durable at cursor + 1 and skips nothing it has not written. That happens at every start (a
 * resumed durable redelivers a killed process's unacked window interleaved with new messages, and the AckPolicy.All
 * ack of a later one retires the earlier, never-written ones: the bake-off lost 19 of 360M that way), on any gap in
 * the consumer sequence (a pull response lost with its connection, FR-DRS-33), and after a failed write. The ack
 * follows the store's durable INSERT and only moves the stream's ack floor for retention and monitoring; correctness
 * never rests on it.
 */
final class FeedLane implements Runnable {
  static final Duration ACK_WAIT = Duration.ofSeconds(60);
  static final long DAY_MS = 86_400_000L;

  final String stream, durable, subject, table;
  final int part;
  private final Connection nc;
  private final ClickHouseStore store;
  private final int batchRows, flushMs, fetch;
  private final JetStreamOptions jso = JetStreamOptions.builder().requestTimeout(Duration.ofMinutes(10)).build();

  final AtomicLong rows = new AtomicLong(), recreates = new AtomicLong(), cursor = new AtomicLong(),
      writeFailures = new AtomicLong(), lastWriteMs = new AtomicLong();
  volatile String lastError = "";
  volatile boolean ready;
  private volatile JetStreamSubscription sub;

  FeedLane(Connection nc, ClickHouseStore store, String stream, String feed, int part, int batchRows, int flushMs, int fetch) {
    this.nc = nc; this.store = store; this.stream = stream; this.table = feed; this.part = part;
    this.subject = "/" + feed + "." + part; this.durable = "history-" + feed + "-p" + part;
    this.batchRows = batchRows; this.flushMs = flushMs; this.fetch = fetch;
  }

  /** Undelivered plus delivered-but-unacked for this lane's durable; -1 when unknown. */
  long pending() {
    try { var ci = sub.getConsumerInfo(); return ci.getNumPending() + ci.getNumAckPending(); }
    catch (Exception e) { return -1; }
  }

  @Override public void run() {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        consume();
      } catch (InterruptedException e) {
        return;
      } catch (Exception e) {
        lastError = e.toString();
        System.err.printf("%s: %s; reopening at the store's cursor%n", durable, e);
        try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
      }
    }
  }

  /** One consumer lifetime: open at cursor + 1, then fetch, write, ack until a gap or a failure ends it. */
  private void consume() throws Exception {
    long c = store.maxSeq(table, part);
    cursor.set(c);
    open(c);
    ready = true;
    Rows b = new Rows(table.equals("trades"), batchRows);
    Rows.Parser parser = new Rows.Parser();
    long expectDelivery = 1;           // a fresh consumer numbers its deliveries from 1
    long batchStart = 0;
    Message lastInBatch = null;
    while (true) {
      List<Message> msgs = sub.fetch(Math.min(fetch, batchRows - b.n), Duration.ofMillis(100));
      for (Message m : msgs) {
        var md = m.metaData();
        if (md.consumerSequence() != expectDelivery) {     // FR-DRS-33: a delivery this process never received
          flush(b, lastInBatch);
          throw new IllegalStateException("consumer sequence jumped " + expectDelivery + " -> " + md.consumerSequence());
        }
        expectDelivery++;
        long seq = md.streamSequence();
        if (seq <= cursor.get()) continue;                  // cannot happen on a consumer opened at cursor + 1
        parser.parse(m.getData(), b, seq);
        if (b.n > 1 && b.ts[b.n - 1] / DAY_MS != b.ts[0] / DAY_MS) {
          // One INSERT is atomic only within one date partition: write the earlier day first.
          b.n--;
          flush(b, lastInBatch);
          parser.parse(m.getData(), b, seq);
        }
        if (b.n == 1) batchStart = System.nanoTime();
        lastInBatch = m;
      }
      if (b.n >= batchRows || (b.n > 0 && System.nanoTime() - batchStart >= flushMs * 1_000_000L)) {
        flush(b, lastInBatch);
        lastInBatch = null;
      }
    }
  }

  /** Durable INSERT, then the ack. A failed INSERT ends the consumer lifetime: the next one reopens at the cursor. */
  private void flush(Rows b, Message last) throws Exception {
    if (b.n == 0) return;
    long t = System.nanoTime();
    try {
      store.insert(b, part);
    } catch (Exception e) {
      writeFailures.incrementAndGet();
      b.n = 0;
      throw e;
    }
    lastWriteMs.set((System.nanoTime() - t) / 1_000_000);
    rows.addAndGet(b.n);
    cursor.set(b.lastSeq());
    b.n = 0;
    if (last != null) last.ack();
  }

  private void open(long c) throws Exception {
    if (sub != null) { try { sub.unsubscribe(); } catch (Exception ignored) { } recreates.incrementAndGet(); }
    JetStreamManagement jsm = nc.jetStreamManagement(jso);
    try { jsm.deleteConsumer(stream, durable); } catch (JetStreamApiException missing) { }
    ConsumerConfiguration.Builder cc = ConsumerConfiguration.builder().durable(durable).filterSubject(subject)
        .ackPolicy(AckPolicy.All).ackWait(ACK_WAIT).maxAckPending(-1);
    if (c == 0) cc.deliverPolicy(DeliverPolicy.All);
    else cc.deliverPolicy(DeliverPolicy.ByStartSequence).startSequence(c + 1);
    long t0 = System.nanoTime();
    jsm.addOrUpdateConsumer(stream, cc.build());
    sub = nc.jetStream(jso).subscribe(null, PullSubscribeOptions.bind(stream, durable));
    System.out.printf("%s on %s %s: open at seq %d (%.1f s)%n", durable, stream, subject, c + 1, (System.nanoTime() - t0) / 1e9);
  }
}
