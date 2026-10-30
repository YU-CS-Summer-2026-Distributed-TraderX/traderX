package finos.traderx.tradeprocessor;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * YU18 downstream redesign (docs/engineering/downstream-at-400k.md, direction c): the projection
 * pulls from a REPLAYABLE, PARTITIONED feed instead of a core subscription.
 *
 * <p>The bridges still core-publish, non-blocking, exactly as before; a JetStream stream on the same
 * subjects captures what they publish, so a slow or restarting projection sees LAG instead of loss.
 * Each partition ({@code /trades.<p>}, {@code /orders.<p>}, {@code p = accountId mod P}) is pulled by
 * one thread with one durable consumer, in batches, and the batch is acked only after the handler
 * returned - i.e. after the SQL commit - so delivery is at-least-once and the writers' existing
 * idempotency (trade id, order {@code (seq, ordinal)}) makes a replay harmless. Partitions are
 * disjoint by account, so positions and orders never cross a partition and the partitions commit
 * in parallel ({@link finos.traderx.tradeprocessor.service.ProjectionWriteLock} is per partition).
 *
 * <p>ponytail: one stream, file storage, size/age limits; a cursor committed INSIDE the SQL
 * transaction (DOWNSTREAM.md §5 item 3) would make it exactly-once - not needed while the writers
 * are idempotent.
 */
public final class JetStreamFeed {
  private static final Logger log = LoggerFactory.getLogger(JetStreamFeed.class);

  private JetStreamFeed() {}

  /** Partition subject the bridges publish on: the bare subject for a single partition. */
  public static String subject(String base, int partition, int partitionCount) {
    return partitionCount <= 1 ? base : base + "." + partition;
  }

  /** Owned partitions from configuration: {@code "all"} or a list such as {@code "0,2"}. */
  public static int[] owned(String owned, int partitionCount) {
    int count = Math.max(1, partitionCount);
    if (owned == null || owned.isBlank() || "all".equalsIgnoreCase(owned.trim())) {
      int[] all = new int[count];
      for (int i = 0; i < all.length; i++) { all[i] = i; }
      return all;
    }
    String[] parts = owned.split(",");
    int[] out = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      out[i] = Integer.parseInt(parts[i].trim());
      if (out[i] < 0 || out[i] >= count) {
        throw new IllegalArgumentException("owned partition " + out[i] + " outside 0.." + (count - 1));
      }
    }
    return out;
  }

  /** Idempotently create the capture stream over both feeds' subjects (bare and partitioned). */
  public static void ensureStream(Connection nc, String stream, long maxBytes, Duration maxAge) throws Exception {
    var jsm = nc.jetStreamManagement();
    StreamConfiguration existing = null;
    try {
      existing = jsm.getStreamInfo(stream).getConfiguration();
    } catch (JetStreamApiException notFound) {
      // fall through and create
    }
    if (existing != null) {
      // The retention limits are sized in the manifests (downstream-at-400k.md §7.5); a stream created
      // under older limits is brought to them, so the configured outage window is the one in force.
      if (existing.getMaxBytes() != maxBytes || !maxAge.equals(existing.getMaxAge())) {
        jsm.updateStream(StreamConfiguration.builder(existing).maxBytes(maxBytes).maxAge(maxAge).build());
        log.info("JetStream stream {} limits updated: maxBytes {} -> {}, maxAge {} -> {}", stream,
            existing.getMaxBytes(), maxBytes, existing.getMaxAge(), maxAge);
      }
      return;
    }
    jsm.addStream(StreamConfiguration.builder()
        .name(stream)
        .subjects("/trades", "/trades.*", "/orders", "/orders.*")
        .storageType(StorageType.File)
        .maxBytes(maxBytes)
        .maxAge(maxAge)
        .build());
    log.info("JetStream stream {} created (maxBytes={}, maxAge={})", stream, maxBytes, maxAge);
  }

  /**
   * Precondition for a cursor-resumed view (the trade store, the order view snapshot): replay starts at
   * {@code cursor + 1}, so a stream that no longer holds that message, or is not the stream the cursor was taken
   * from, would skip messages silently. Refuse instead, naming both sequences ({@code what} names the view).
   */
  public static void requireRetained(Connection nc, String stream, String what, int partition, long cursor) throws Exception {
    var state = nc.jetStreamManagement().getStreamInfo(stream).getStreamState();
    long first = state.getFirstSequence();
    if (state.getLastSequence() < cursor) {
      // Sequences never go backwards in one stream: this is a different (recreated) stream, e.g. NATS
      // restarted on an emptyDir. Replaying it would ignore every seq <= cursor, i.e. drop it silently.
      throw new IllegalStateException("STREAM_RESET " + what + " partition " + partition + ": the snapshot cursor is at"
          + " stream sequence " + cursor + " but " + stream + " ends at " + state.getLastSequence()
          + "; the stream was recreated, so it is not the ledger this snapshot was taken from");
    }
    if (first > cursor + 1) {
      throw new IllegalStateException("RETENTION_GAP " + what + " partition " + partition + ": the snapshot cursor is at"
          + " stream sequence " + cursor + " but the oldest message " + stream + " retains is " + first + "; sequences "
          + (cursor + 1) + ".." + (first - 1) + " were discarded before this partition applied them");
    }
  }

  /** The stream sequence of the last message stored on {@code subject}, 0 if it holds none. */
  public static long lastSeq(Connection nc, String stream, String subject) throws Exception {
    try {
      return nc.jetStreamManagement().getLastMessage(stream, subject).getSeq();
    } catch (JetStreamApiException none) {
      if (none.getErrorCode() == 404) { return 0; }
      throw none;
    }
  }

  /** A durable consumer that resumes where its last ack left off: the SQL projections. */
  public static ConsumerConfiguration durable(String name, String filterSubject) {
    return ConsumerConfiguration.builder()
        .durable(name)
        .filterSubject(filterSubject)
        .ackPolicy(AckPolicy.All)
        .ackWait(Duration.ofSeconds(60))
        .deliverPolicy(DeliverPolicy.All)
        .build();
  }

  /** Batches a pipelined consumer may hold unacknowledged: the one being applied, the one queued, the one in flight. */
  static final int PIPELINE_DEPTH = 4;

  /** An ephemeral consumer from a stream sequence: the trade store, whose cursor is its own ack. */
  public static ConsumerConfiguration fromSequence(String filterSubject, long startSequence) {
    return fromSequence(filterSubject, startSequence, 1000);
  }

  /** As above, allowed {@link #PIPELINE_DEPTH} batches of {@code batch} unacknowledged, so a pipelined pull can overlap. */
  public static ConsumerConfiguration fromSequence(String filterSubject, long startSequence, int batch) {
    ConsumerConfiguration.Builder b = ConsumerConfiguration.builder()
        .filterSubject(filterSubject)
        .ackPolicy(AckPolicy.All)
        .ackWait(Duration.ofSeconds(60))
        .maxAckPending((long) batch * PIPELINE_DEPTH)
        .inactiveThreshold(Duration.ofMinutes(5));
    if (startSequence <= 1) { b.deliverPolicy(DeliverPolicy.All); }
    else { b.deliverPolicy(DeliverPolicy.ByStartSequence).startSequence(startSequence); }
    return b.build();
  }

  /** An ephemeral consumer that replays the last {@code replay} of the subject: the in-memory views. */
  public static ConsumerConfiguration replayWindow(String filterSubject, Duration replay) {
    return ConsumerConfiguration.builder()
        .filterSubject(filterSubject)
        .ackPolicy(AckPolicy.All)
        .ackWait(Duration.ofSeconds(60))
        .deliverPolicy(DeliverPolicy.ByStartTime)
        .startTime(ZonedDateTime.now().minus(replay))
        .inactiveThreshold(Duration.ofMinutes(5))
        .build();
  }

  /**
   * Pull loop on its own daemon thread: fetch up to {@code batch} messages (waiting at most
   * {@code maxWait} for the first), hand them to {@code handler}, ack the last one. A handler that
   * throws leaves the batch unacked, so it is redelivered after ackWait.
   */
  public static Thread start(String threadName, Connection nc, String stream, String filterSubject,
                             ConsumerConfiguration consumer, int batch, Duration maxWait,
                             Consumer<List<Message>> handler) {
    return start(threadName, nc, stream, filterSubject, consumer, batch, maxWait, handler, null);
  }

  /** As above; {@code idle}, if given, is told the stream's last sequence whenever this consumer is
   *  caught up (empty fetch, nothing pending), at most every {@link #IDLE_EVERY}. */
  public static Thread start(String threadName, Connection nc, String stream, String filterSubject,
                             ConsumerConfiguration consumer, int batch, Duration maxWait,
                             Consumer<List<Message>> handler, java.util.function.LongConsumer idle) {
    Thread t = new Thread(() -> run(nc, stream, filterSubject, consumer, batch, maxWait, handler, idle, true), threadName);
    t.setDaemon(true);
    t.start();
    return t;
  }

  static final Duration IDLE_EVERY = Duration.ofSeconds(5);

  /**
   * FR-DRS-31: the same loop split in two threads, so the next pull is in flight while the previous batch is applied.
   * Measured (downstream-at-400k.md §7.12): under ingest a lagging consumer is served ~70 ms per 1,000-message pull, and
   * the serial fetch-apply-ack loop added the apply time to every pull. Only for consumers whose cursor, committed
   * with the view's snapshot, is the acknowledgement (the trade store, the order view snapshot): a batch acked ahead of
   * an earlier one that failed would be lost to an ack-driven consumer, but a restart here resumes from the cursor.
   * Batches and idle notices reach the apply thread in fetch order, so {@code idle} still follows every message the
   * stream held at its head.
   */
  public static Thread startPipelined(String threadName, Connection nc, String stream, String filterSubject,
                                      ConsumerConfiguration consumer, int batch, Duration maxWait,
                                      Consumer<List<Message>> handler, java.util.function.LongConsumer idle) {
    java.util.concurrent.BlockingQueue<Object> handoff = new java.util.concurrent.ArrayBlockingQueue<>(2);
    Thread apply = new Thread(() -> {
      try {
        while (true) {
          Object next = handoff.take();
          if (next instanceof Long head) { idle.accept(head); continue; }
          @SuppressWarnings("unchecked") List<Message> msgs = (List<Message>) next;
          try {
            handler.accept(msgs);
            msgs.get(msgs.size() - 1).ack();
          } catch (Exception x) {
            log.error("JetStream feed {} batch of {} failed; the cursor still names the last applied message", filterSubject, msgs.size(), x);
          }
        }
      } catch (InterruptedException stop) {
        Thread.currentThread().interrupt();
      }
    }, threadName + "-apply");
    apply.setDaemon(true);
    apply.start();
    java.util.function.LongConsumer queuedIdle = idle == null ? null : head -> {
      try { handoff.put(head); } catch (InterruptedException x) { Thread.currentThread().interrupt(); }
    };
    Thread fetch = new Thread(() -> {
      try {
        run(nc, stream, filterSubject, consumer, batch, maxWait, msgs -> {
          try { handoff.put(msgs); } catch (InterruptedException x) { Thread.currentThread().interrupt(); throw new IllegalStateException(x); }
        }, queuedIdle, false);
      } finally {
        apply.interrupt();   // a stopped feed applies nothing more
      }
    }, threadName);
    fetch.setDaemon(true);
    fetch.start();
    return fetch;
  }

  private static void run(Connection nc, String stream, String filterSubject, ConsumerConfiguration consumer,
                          int batch, Duration maxWait, Consumer<List<Message>> handler,
                          java.util.function.LongConsumer idle, boolean ackHere) {
    long nextIdle = 0;
    JetStreamSubscription sub;
    try {
      sub = nc.jetStream().subscribe(filterSubject,
          PullSubscribeOptions.builder().stream(stream).configuration(consumer).build());
    } catch (Exception x) {
      log.error("JetStream feed {} could not subscribe; this partition is NOT being projected", filterSubject, x);
      return;
    }
    log.info("JetStream feed {} pulling from stream {} ({})", filterSubject, stream,
        consumer.getDurable() == null ? "ephemeral replay window" : "durable " + consumer.getDurable());
    while (!Thread.currentThread().isInterrupted()) {
      List<Message> msgs;
      try {
        msgs = sub.fetch(batch, maxWait);
      } catch (Exception x) {
        if (Thread.currentThread().isInterrupted()) { return; }
        log.warn("JetStream feed {} fetch failed: {}", filterSubject, x.toString());
        try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
        continue;
      }
      if (msgs.isEmpty()) {
        if (idle != null && System.nanoTime() >= nextIdle) {
          nextIdle = System.nanoTime() + IDLE_EVERY.toNanos();
          try {
            // Stream head FIRST, then pending: pending 0 afterwards means no message of this subject at or
            // below that head is undelivered, and every delivered one was handled before this fetch.
            long head = nc.jetStreamManagement().getStreamInfo(stream).getStreamState().getLastSequence();
            if (sub.getConsumerInfo().getNumPending() == 0) { idle.accept(head); }
          } catch (Exception x) {
            log.debug("JetStream feed {} idle check failed: {}", filterSubject, x.toString());
          }
        }
        continue;
      }
      try {
        handler.accept(msgs);
        if (ackHere) { msgs.get(msgs.size() - 1).ack(); }
      } catch (Exception x) {
        if (Thread.currentThread().isInterrupted()) { return; }
        log.error("JetStream feed {} batch of {} failed; left unacked for redelivery", filterSubject, msgs.size(), x);
      }
    }
  }
}
