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

  FeedLane(Connection nc, ClickHouseStore store, String stream, String feed, int part, int partitions, int batchRows,
           int flushMs, int fetch) {
    this.nc = nc; this.store = store; this.stream = stream; this.table = feed; this.part = part;
    this.subject = HistoryFeedMain.subjectFor(feed, part, partitions); this.durable = "history-" + feed + "-p" + part;
    this.batchRows = batchRows; this.flushMs = flushMs; this.fetch = fetch;
  }

  /**
   * FR-THT-10: the ack follows the INSERT, so the unacked window must hold a whole batch plus the fetch in flight, or the
   * server stops delivering mid-batch and the lane writes one window per flush interval.
   */
  static long maxAckPending(int batchRows, int fetch) { return 2L * batchRows + fetch; }

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
      } catch (IncarnationRefused e) {
        // FR-HST-07: stay down, loudly, until an operator reconciles the store with the new stream.
        lastError = e.getMessage();
        System.err.println("ALERT " + e.getMessage());
        try { Thread.sleep(30_000); } catch (InterruptedException ie) { return; }
      } catch (Exception e) {
        lastError = e.toString();
        System.err.printf("%s: %s; reopening at the store's cursor%n", durable, e);
        try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
      }
    }
  }

  /** One consumer lifetime: open at cursor + 1, then fetch, write, ack until a gap or a failure ends it. */
  private void consume() throws Exception {
    ready = false;
    long c = store.maxSeq(table, part);
    cursor.set(c);
    checkIncarnation(c);
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

  /**
   * FR-HST-07: a cursor is only a number. A stream deleted and recreated under the same name restarts its sequences,
   * and once it passes the cursor, opening at cursor + 1 would silently skip its first messages (codeX review §6).
   * So the rows are bound to the stream's incarnation, its creation time (the scheme trade-processor uses, FR-DRS-36),
   * and a lane opens only on the incarnation its rows came from.
   */
  private void checkIncarnation(long c) throws Exception {
    String current = incarnationOf(nc.jetStreamManagement(jso).getStreamInfo(stream));
    String bound = store.incarnation(stream, table, part);
    if (current.equals(bound)) return;
    if (c == 0) {                                     // an empty lane skips nothing
      store.bind(stream, table, part, current);
      System.out.printf("%s: bound to %s incarnation %s%n", durable, stream, current);
      return;
    }
    // Rows with no recorded stream are never bound to whatever stream exists now: an operator reconciles them and
    // writes the binding itself, with the statement below (FR-HST-07; codeX pass 3 §16; trade-processor's FR-DRS-36).
    if (bound == null) throw new IncarnationRefused("history-feed STREAM_UNBOUND " + durable + ": the store holds " + table
        + " part " + part + " up to seq " + c + " with no record of which " + stream + " they came from, so resuming at seq "
        + (c + 1) + " may skip the current stream's first messages. Reconcile this lane's rows against " + stream
        + " created " + current + ", then attest with: " + attestation(stream, table, part, current, c)
        + " ; or move the rows aside to rebuild. Then restart.");
    throw new IncarnationRefused("history-feed STREAM_RESET " + durable + ": the store holds " + table + " part " + part
        + " up to seq " + c + " from " + stream + " created " + bound + ", but " + stream + " is now created " + current
        + "; resuming at seq " + (c + 1) + " would skip its first " + c + " messages. Reconcile the store (move this lane's"
        + " rows aside), then restart.");
  }

  /**
   * The operator's attestation: the binding itself, the reconciled stream's creation time, written only while the lane
   * is still unbound and still holds exactly the cursor reconciled. A replay, a bound lane or a moved cursor writes
   * nothing; a stream recreated after it is an ordinary mismatch (STREAM_RESET).
   */
  static String attestation(String stream, String table, int part, String incarnation, long cursor) {
    return "INSERT INTO history_cursor (stream, tbl, part, incarnation) SELECT '" + stream + "', '" + table + "', " + part
        + ", '" + incarnation + "' WHERE (SELECT count() FROM history_cursor WHERE stream = '" + stream + "' AND tbl = '"
        + table + "' AND part = " + part + ") = 0 AND (SELECT max(seq) FROM " + table + " WHERE part = " + part + ") = "
        + cursor;
  }

  /** A stream's incarnation: its creation time, unchanged for its whole life, new whenever it is recreated. */
  static String incarnationOf(io.nats.client.api.StreamInfo si) {
    return si.getCreateTime().toInstant().toString();
  }

  static final class IncarnationRefused extends Exception {
    IncarnationRefused(String message) { super(message); }
  }

  private void open(long c) throws Exception {
    if (sub != null) { try { sub.unsubscribe(); } catch (Exception ignored) { } recreates.incrementAndGet(); }
    JetStreamManagement jsm = nc.jetStreamManagement(jso);
    try { jsm.deleteConsumer(stream, durable); } catch (JetStreamApiException missing) { }
    ConsumerConfiguration.Builder cc = ConsumerConfiguration.builder().durable(durable).filterSubject(subject)
        .ackPolicy(AckPolicy.All).ackWait(ACK_WAIT).maxAckPending(maxAckPending(batchRows, fetch));
    if (c == 0) cc.deliverPolicy(DeliverPolicy.All);
    else cc.deliverPolicy(DeliverPolicy.ByStartSequence).startSequence(c + 1);
    long t0 = System.nanoTime();
    long granted = jsm.addOrUpdateConsumer(stream, cc.build()).getConsumerConfiguration().getMaxAckPending();
    // FR-THT-10: the server's word, not ours. -1 was stored as its default 1000 (2026-11-08, 1,500 rows/s in production).
    if (granted < batchRows) throw new IllegalStateException(durable + ": the server granted maxAckPending " + granted
        + " < HISTORY_BATCH_ROWS " + batchRows + "; every batch would wait out its flush interval");
    sub = nc.jetStream(jso).subscribe(null, PullSubscribeOptions.bind(stream, durable));
    System.out.printf("%s on %s %s: open at seq %d (%.1f s)%n", durable, stream, subject, c + 1, (System.nanoTime() - t0) / 1e9);
  }
}
