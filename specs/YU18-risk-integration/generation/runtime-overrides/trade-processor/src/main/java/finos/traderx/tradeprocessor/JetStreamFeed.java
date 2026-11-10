package finos.traderx.tradeprocessor;

import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.CompressionOption;
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

  /**
   * ADR-097 (FR-DRS-32): the stream partition {@code p} is captured in. Layout "one" (the default): every subject in
   * {@code base}. Layout "partition" with P > 1: one stream per partition, {@code <base>_P<p>}, capturing
   * {@code /trades.<p>} and {@code /orders.<p>}. A stream is one serial ingest and delivery path in the server, and with
   * one stream it was the read model's ceiling (downstream-at-400k.md §7.12).
   */
  public static String streamFor(String base, String layout, int partition, int partitionCount) {
    return "partition".equalsIgnoreCase(layout) && partitionCount > 1 ? base + "_P" + partition : base;
  }

  /** Create (or bring to its limits) every stream the layout needs; per-partition streams share the byte limit. */
  public static void ensureStreams(Connection nc, String base, String layout, int partitionCount, long maxBytes, Duration maxAge)
      throws Exception {
    if (streamFor(base, layout, 0, partitionCount).equals(base)) { ensureStream(nc, base, maxBytes, maxAge); return; }
    for (int p = 0; p < partitionCount; p++) {
      ensureStream(nc, streamFor(base, layout, p, partitionCount), maxBytes / partitionCount, maxAge, "/trades." + p, "/orders." + p);
    }
  }

  /** Idempotently create the capture stream over both feeds' subjects (bare and partitioned). */
  public static void ensureStream(Connection nc, String stream, long maxBytes, Duration maxAge) throws Exception {
    ensureStream(nc, stream, maxBytes, maxAge, "/trades", "/trades.*", "/orders", "/orders.*");
  }

  /** FR-DRS-42: {@code PROJECTION_FEED_STREAM_STORAGE=memory} keeps the capture stream in the server's RAM (the
   *  throughput rig: no volume under the stream; a server restart empties it, which the readers' STREAM_RESET path
   *  and the bridges' catch-up already cover); unset or {@code file} is the production setting. */
  /** s2 is a file-store property; a memory stream has no compression. */
  static CompressionOption compression(StorageType storage) {
    return storage == StorageType.File ? CompressionOption.S2 : CompressionOption.None;
  }

  static StorageType storageType() {
    String s = System.getenv("PROJECTION_FEED_STREAM_STORAGE");
    if (s == null || s.isEmpty() || s.equalsIgnoreCase("file")) return StorageType.File;
    if (s.equalsIgnoreCase("memory")) return StorageType.Memory;
    throw new IllegalArgumentException("PROJECTION_FEED_STREAM_STORAGE must be file or memory: " + s);
  }

  static void ensureStream(Connection nc, String stream, long maxBytes, Duration maxAge, String... subjects) throws Exception {
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
      // FR-DRS-42: the stream is s2-compressed on disk. A stream created before that is brought to it; compression
      // applies to the blocks written from then on.
      if (existing.getMaxBytes() != maxBytes || !maxAge.equals(existing.getMaxAge())
          || existing.getCompressionOption() != compression(existing.getStorageType())) {
        jsm.updateStream(StreamConfiguration.builder(existing).maxBytes(maxBytes).maxAge(maxAge)
            .compressionOption(compression(existing.getStorageType())).build());
        log.info("JetStream stream {} updated: maxBytes {} -> {}, maxAge {} -> {}, compression {} -> s2", stream,
            existing.getMaxBytes(), maxBytes, existing.getMaxAge(), maxAge, existing.getCompressionOption());
      }
      return;
    }
    jsm.addStream(StreamConfiguration.builder()
        .name(stream)
        .subjects(subjects)
        .storageType(storageType())
        .compressionOption(compression(storageType()))
        .maxBytes(maxBytes)
        .maxAge(maxAge)
        .build());
    log.info("JetStream stream {} created (maxBytes={}, maxAge={}, {}, s2)", stream, maxBytes, maxAge, storageType());
  }

  /**
   * Precondition for a cursor-resumed view (the trade store, the order view snapshot): replay starts at
   * {@code cursor + 1}, so a stream that no longer holds that message, or is not the stream the cursor was taken
   * from, would skip messages silently. Refuse instead, naming both sequences ({@code what} names the view).
   *
   * <p>FR-DRS-36: a sequence is not an identity. A stream deleted and recreated under the same name (NATS storage lost,
   * SQL kept) that has grown past the cursor passes both sequence checks, and resuming at {@code cursor + 1} skips its
   * first {@code cursor} messages. {@code bound} is the creation time (epoch ns) of the stream the cursor was taken on,
   * null for a cursor recorded before it was; a cursor past 0 on another stream is refused. Returns this stream's
   * creation time, for the caller to bind to its cursor.
   *
   * <p>A first binding needs evidence, not assumption: an unbound cursor past 0 may already be on a recreated stream,
   * so it is refused ({@link Unbound}) until an operator reconciles the read model and attests the row. The attestation
   * is the binding itself, the creation time of the stream that was reconciled, written only to that scope, partition
   * and cursor while the row is still unbound: a stream recreated after it is then refused like any other
   * ({@code STREAM_RESET}), and replaying the statement matches no row. A cursor at 0 skips nothing and binds.
   */
  public static long requireRetained(Connection nc, String stream, String what, String cursorScope, int partition,
      long cursor, Long bound) throws Exception {
    var info = nc.jetStreamManagement().getStreamInfo(stream);
    long created = createdNs(info);
    if (cursor > 0 && bound == null) {
      throw new Unbound("STREAM_UNBOUND " + what + " partition " + partition + ": the snapshot cursor (stream sequence "
          + cursor + ") was saved before cursors were bound to their stream, so nothing shows it was taken on " + stream
          + " as created at " + instant(created) + "; if that stream was recreated, resuming would skip its first messages."
          + " Reconcile the read model (rig G3, FR-DRS-33 recon), then attest this stream: " + attestation(cursorScope,
          partition, cursor, created) + " Or rebuild: UPDATE projection_cursor SET stream_seq=0 for this scope and partition.");
    }
    if (cursor > 0 && bound != null && bound != created) {
      throw new IllegalStateException("STREAM_RESET " + what + " partition " + partition + ": the snapshot cursor (stream"
          + " sequence " + cursor + ") was taken on the stream " + stream + " created at " + instant(bound) + ", but " + stream
          + " was created at " + instant(created) + "; resuming would skip its first " + cursor + " messages. Reconcile, then"
          + " rebind: UPDATE projection_cursor SET stream_seq=0, stream_created_ns=NULL for this scope and partition");
    }
    var state = info.getStreamState();
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
    return created;
  }

  /** An unbound cursor past 0 (FR-DRS-36); a view collects every partition's before refusing, so one start lists all. */
  public static final class Unbound extends IllegalStateException {
    Unbound(String message) { super(message); }
  }

  /**
   * The operator's attestation of one reconciled row: binds it to the stream {@code created} names, and only if the row
   * still holds that cursor unbound, so a replay, or a run against a row that moved since, changes nothing.
   */
  static String attestation(String cursorScope, int partition, long cursor, long created) {
    return "UPDATE projection_cursor SET stream_created_ns=" + created + " WHERE projection_scope='"
        + cursorScope.replace("'", "''") + "' AND partition_id=" + partition + " AND stream_seq=" + cursor
        + " AND stream_created_ns IS NULL;";
  }

  /** A stream's identity: its creation time in epoch ns. An update (limits) keeps it; a recreate does not. */
  static long createdNs(io.nats.client.api.StreamInfo info) {
    java.time.Instant t = info.getCreateTime().toInstant();
    return t.getEpochSecond() * 1_000_000_000L + t.getNano();
  }

  private static java.time.Instant instant(long ns) {
    return java.time.Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L));
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

  /**
   * Bytes a fetch aims for (FR-DRS-43). A fetch is sized in messages, and FR-DRS-41 made a message under load 20-40
   * records (~12 KB) where it was one (~400 B): a 1,000-message fetch per partition, 16 partitions on one connection,
   * two or three fetches in flight, is hundreds of MB the server must buffer for this client, and it cuts the
   * connection at 64 MB pending ("Slow Consumer Detected"; GKE 2026-11-09: 351 cuts in 5 min at 1.4M records/s, the
   * read model 4-5M messages behind after each). The next fetch asks for as many messages as the last batch's average
   * size fits in this, so the bytes in flight stay bounded whatever the wire's batching; at the one-record wire the
   * count is the configured batch, as before.
   */
  static final long FETCH_BYTES = 1L << 20;

  /** FR-DRS-43: the message count of the next fetch, from the configured {@code batch} and the last batch's bytes. */
  static int nextFetch(int batch, long lastBytes, int lastCount) {
    if (lastCount <= 0 || lastBytes <= 0) return batch;
    long avg = Math.max(1, lastBytes / lastCount);
    return (int) Math.max(1, Math.min(batch, FETCH_BYTES / avg));
  }

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
   * throws has the batch redelivered: the feed resubscribes at its first message (FR-DRS-33).
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
   * with the view's snapshot, is the acknowledgement (the trade store, the order view snapshot). A batch that fails is
   * applied again until it succeeds (FR-DRS-35), so every batch handed to the apply thread is applied, in fetch order.
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
          // FR-DRS-35: a batch that fails is a barrier. It is applied again until it succeeds, and nothing queued behind
          // it is applied first: a later batch would move the cursor past it. The handler skips a refused message itself.
          for (long backoff = 100; ; backoff = Math.min(backoff * 2, 5000)) {
            try {
              handler.accept(msgs);
              msgs.get(msgs.size() - 1).ack();
              break;
            } catch (RuntimeException x) {
              log.error("JetStream feed {} batch of {} failed; applying it again in {} ms, and nothing after it before then",
                  filterSubject, msgs.size(), backoff, x);
              Thread.sleep(backoff);
            }
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

  /**
   * FR-DRS-33: every consumer here acks cumulatively (AckPolicy.All, the last of each batch), and the server redelivers
   * a delivered-but-unacked message only after ackWait. So a message delivered and never applied (lifetime A killed
   * after the fetch; a pull response lost with its connection) is retired by the next batch's ack. Measured on a real
   * JetStream (JetStreamFeedAckGapIT): without this, all three such cases lose the messages. The consumer sequence
   * names every delivery, so a jump is exactly such a loss: the feed then recreates its consumer at the last message
   * it applied + 1. A durable resumes the same way at every start, from its ack floor (the ack follows the apply).
   */
  private static void run(Connection nc, String stream, String filterSubject, ConsumerConfiguration consumer,
                          int batch, Duration maxWait, Consumer<List<Message>> handler,
                          java.util.function.LongConsumer idle, boolean ackHere) {
    long nextIdle = 0;
    JetStreamSubscription sub = null;
    long last = -1;     // stream sequence of the last message handed to the handler, -1 before the first
    long expect = 0;    // consumer sequence the next delivery must carry
    boolean started = false;
    int want = batch;   // FR-DRS-43: the next fetch's message count, from the last batch's bytes
    long created = 0;   // FR-DRS-36: the stream this feed's sequences belong to
    while (!Thread.currentThread().isInterrupted()) {
      if (sub == null) {
        try {
          long now = createdNs(nc.jetStreamManagement().getStreamInfo(stream));
          if (started && now != created) {
            // Recreated under this feed (NATS lost its storage): resuming at last + 1 would skip the new stream's
            // first messages. A durable (the idempotent row projections) reads the new stream from its start, as a
            // restart would. A cursor-acknowledged view stops: a restart resumes from its committed cursor, which
            // requireRetained refuses on another stream.
            if (consumer.getDurable() == null) {
              log.error("ALERT JetStream feed {}: stream {} was recreated ({} -> {}); this partition is NOT being projected"
                  + " and will not resume across streams", filterSubject, stream, instant(created), instant(now));
              return;
            }
            log.warn("JetStream feed {}: stream {} was recreated ({} -> {}); reading it from its first message",
                filterSubject, stream, instant(created), instant(now));
            last = -1;
          }
          created = now;
          sub = nc.jetStream().subscribe(filterSubject,
              PullSubscribeOptions.builder().stream(stream).configuration(resume(nc, stream, consumer, last)).build());
          expect = sub.getConsumerInfo().getDelivered().getConsumerSequence() + 1;
          started = true;
        } catch (Exception x) {
          if (!started) {
            log.error("JetStream feed {} could not subscribe; this partition is NOT being projected", filterSubject, x);
            return;
          }
          log.warn("JetStream feed {} could not resubscribe at {}: {}", filterSubject, last + 1, x.toString());
          try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
          continue;
        }
        log.info("JetStream feed {} pulling from stream {} ({})", filterSubject, stream,
            consumer.getDurable() == null ? "ephemeral" : "durable " + consumer.getDurable());
      }
      List<Message> msgs;
      try {
        msgs = sub.fetch(want, maxWait);
      } catch (Exception x) {
        if (Thread.currentThread().isInterrupted()) { return; }
        // E.g. an ephemeral consumer the server deleted past its inactive threshold while the apply thread held this
        // one at a barrier (FR-DRS-35): a new consumer resumes after the last message handed to the handler.
        log.warn("JetStream feed {} fetch failed: {}; resuming at stream sequence {}", filterSubject, x.toString(), last + 1);
        sub = drop(sub);
        try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
        continue;
      }
      if (!msgs.isEmpty()) {
        long bytes = 0;
        for (Message m : msgs) { bytes += m.getData().length; }
        want = nextFetch(batch, bytes, msgs.size());
        long first = msgs.get(0).metaData().consumerSequence();
        long end = msgs.get(msgs.size() - 1).metaData().consumerSequence();
        if (first != expect || end - first != msgs.size() - 1) {
          log.warn("JetStream feed {} deliveries {}..{} expected from {}: some were delivered and never reached this feed;"
              + " resuming at stream sequence {}", filterSubject, first, end, expect, last + 1);
          sub = drop(sub);
          continue;
        }
        expect = end + 1;
      }
      if (msgs.isEmpty()) {
        if (idle != null && System.nanoTime() >= nextIdle) {
          nextIdle = System.nanoTime() + IDLE_EVERY.toNanos();
          try {
            // Stream head FIRST, then the consumer: no message of this subject at or below that head may be
            // undelivered (pending) or delivered and not yet applied (ack pending: the ack follows the apply).
            // Pending alone is not enough: a message delivered to this client after the empty fetch is not
            // pending, and moving the cursor past it made the next fetch ignore it as a replay. Measured
            // (downstream-at-400k.md §7.12, P=16 at 50K orders/s): 1,408 of 3,000,000 sides lost, caught by the
            // FR-DRS-29 accounting; with ack pending required, every step exact.
            long head = nc.jetStreamManagement().getStreamInfo(stream).getStreamState().getLastSequence();
            var info = sub.getConsumerInfo();
            if (info.getNumPending() == 0 && info.getNumAckPending() == 0) { idle.accept(head); }
          } catch (JetStreamApiException gone) {
            if (gone.getErrorCode() != 404) { log.debug("JetStream feed {} idle check failed: {}", filterSubject, gone.toString()); continue; }
            log.warn("JetStream feed {} consumer is gone ({}); resuming at stream sequence {}", filterSubject, gone.getMessage(), last + 1);
            sub = drop(sub);
          } catch (Exception x) {
            log.debug("JetStream feed {} idle check failed: {}", filterSubject, x.toString());
          }
        }
        continue;
      }
      try {
        handler.accept(msgs);
        if (ackHere) { msgs.get(msgs.size() - 1).ack(); }
        last = msgs.get(msgs.size() - 1).metaData().streamSequence();
      } catch (Exception x) {
        if (Thread.currentThread().isInterrupted()) { return; }
        log.error("JetStream feed {} batch of {} failed; redelivering it from stream sequence {}", filterSubject, msgs.size(), last + 1, x);
        sub = drop(sub);
        try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
      }
    }
  }

  /**
   * The consumer to subscribe with: {@code consumer} itself, except that it starts after {@code last} once anything
   * was applied, and an existing durable is recreated at its ack floor + 1 so nothing it was delivered and did not
   * apply is left for a later cumulative ack to retire (FR-DRS-33).
   */
  static ConsumerConfiguration resume(Connection nc, String stream, ConsumerConfiguration consumer, long last) throws Exception {
    long from = last;
    if (consumer.getDurable() != null) {
      try {
        var info = nc.jetStreamManagement().getConsumerInfo(stream, consumer.getDurable());
        if (from < 0) { from = info.getAckFloor().getStreamSequence(); }
        nc.jetStreamManagement().deleteConsumer(stream, consumer.getDurable());
      } catch (JetStreamApiException none) {
        if (none.getErrorCode() != 404) { throw none; }
      }
    }
    if (from < 0) { return consumer; }
    return ConsumerConfiguration.builder(consumer).startTime(null)
        .deliverPolicy(DeliverPolicy.ByStartSequence).startSequence(from + 1).build();
  }

  /**
   * FR-DRS-35: a failure of the store (the database), not of the message. The message is valid and must be applied
   * again, so it is a barrier: nothing after it is applied and no ack or cursor passes it. Anything else is a refusal of
   * that one message (malformed, failed validation, a constraint), which a handler may skip, logged and counted. A
   * refusal anywhere in the cause chain wins: a constraint inside a transaction exception is still a refusal.
   * ponytail: classified by exception type; a store failure the driver reports as something else is skipped as a refusal.
   */
  public static boolean retryable(Throwable x) {
    for (Throwable c = x; c != null; c = c.getCause()) {
      if (c instanceof org.springframework.dao.DataIntegrityViolationException
          || c instanceof java.sql.SQLIntegrityConstraintViolationException || c instanceof java.sql.SQLDataException) {
        return false;
      }
    }
    for (Throwable c = x; c != null; c = c.getCause()) {
      if (c instanceof org.springframework.dao.DataAccessException
          || c instanceof org.springframework.transaction.TransactionException || c instanceof java.sql.SQLException) {
        return true;
      }
    }
    return false;
  }

  /** {@code x} as an unchecked exception, for a handler rethrowing a {@link #retryable} failure to its feed. */
  public static RuntimeException barrier(Exception x) {
    return x instanceof RuntimeException r ? r : new IllegalStateException(x);
  }

  private static JetStreamSubscription drop(JetStreamSubscription sub) {
    try { sub.unsubscribe(); } catch (Exception ignore) { }
    return null;
  }
}
