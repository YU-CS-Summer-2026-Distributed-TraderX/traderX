package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.RingBuffer;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * FOLLOWER-mode input stream consumer. Subscribes to the primary's JetStream replication
 * subject and injects each received InputEvent into the local input ring so the local BLP
 * processes the identical event sequence without accepting HTTP gateway traffic.
 *
 * <p>Each event is injected via {@link #injectCallback} (a package-private method on
 * {@link LmaxEngine}) that claims a ring slot without registering a gateway ack — so the
 * local MarshallerHandler updates the in-memory read model but has no future to complete.
 *
 * <p>Lifecycle: {@link #start()} blocks until the follower has drained the JetStream stream
 * to the current tail (i.e. caught up to the primary), then signals readiness and switches to
 * live tail mode in a background daemon thread. {@link #stop()} unsubscribes cleanly.
 */
public final class ReplicationFollower {
    private static final Logger log = LoggerFactory.getLogger(ReplicationFollower.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);
    private static final Duration CATCH_UP_TIMEOUT = Duration.ofMillis(50);
    private static final Duration ACK_FLUSH_TIMEOUT = Duration.ofSeconds(2);
    private static final int DURABLE_ACK_QUEUE_CAPACITY = 65_536;
    private static final int DURABLE_ACK_QUEUE_MASK = DURABLE_ACK_QUEUE_CAPACITY - 1;

    enum AckMode {
        ONRING,
        DURABLE;

        static AckMode configured() {
            return parse(System.getenv("BLP_REPLICATION_ACK_MODE"));
        }

        static AckMode parse(String value) {
            if (value == null || value.isBlank() || "onring".equalsIgnoreCase(value)) {
                return ONRING;
            }
            if ("durable".equalsIgnoreCase(value)) {
                return DURABLE;
            }
            log.warn("Unknown BLP_REPLICATION_ACK_MODE='{}'; preserving default onring ACKs", value);
            return ONRING;
        }
    }

    private final Connection conn;
    private final String podName;
    private final long startJetsStreamSeq;   // -1 = deliver all from beginning
    private final Runnable readinessCallback;
    private final InMemoryOrderReadModel readModel;
    private final AckMode ackMode;

    /** Set by LmaxEngine after afterPropertiesSet() wires up the ring. */
    private volatile RingBuffer<InputEvent> inputRing;

    private final AtomicLong lastJetsStreamSeq = new AtomicLong(-1);
    private volatile JetStreamSubscription subscription;
    private volatile Thread followerThread;
    private volatile Thread durableAckThread;
    private volatile boolean running;

    // Pre-allocated ACK buffer — inject() runs on a single thread (tailLoop / drainCatchUp),
    // so this is safe without synchronization and avoids ByteBuffer.allocate() on every event.
    private final ByteBuffer ackBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);

    // Durable mode is a single-producer/single-consumer handoff from the NATS injection thread to
    // the ACK publisher. Each slot maps the follower's local ring sequence to the primary sequence
    // carried in the replication record. The arrays are fixed at startup and pressure the injector
    // when full; no event or ACK watermark is dropped.
    private final long[] durableLocalSequences = new long[DURABLE_ACK_QUEUE_CAPACITY];
    private final long[] durablePrimarySequences = new long[DURABLE_ACK_QUEUE_CAPACITY];
    private long durableNextWriteCursor;
    private volatile long durablePublishedCursor = -1L;
    private volatile long durableConsumedCursor = -1L;

    public ReplicationFollower(Connection conn, String podName, long startJetsStreamSeq,
                               InMemoryOrderReadModel readModel, Runnable readinessCallback) {
        this(conn, podName, startJetsStreamSeq, readModel, readinessCallback, AckMode.configured());
    }

    ReplicationFollower(Connection conn, String podName, long startJetsStreamSeq,
                        InMemoryOrderReadModel readModel, Runnable readinessCallback,
                        AckMode ackMode) {
        this.conn = conn;
        this.podName = podName;
        this.startJetsStreamSeq = startJetsStreamSeq;
        this.readModel = readModel;
        this.readinessCallback = readinessCallback;
        this.ackMode = ackMode;
    }

    /** Called by LmaxEngine after the input ring is created. */
    void setInputRing(RingBuffer<InputEvent> ring) {
        this.inputRing = ring;
    }

    /** Current JetStream sequence position (for snapshot checkpointing). */
    public long lastJetsStreamSeq() {
        return lastJetsStreamSeq.get();
    }

    AckMode ackMode() {
        return ackMode;
    }

    /**
     * Subscribe to JetStream and drain the stream until caught up, then signal readiness and
     * continue tailing in the background. Blocks until the catch-up phase completes.
     */
    public void start() {
        running = true;
        readModel.setReplaying(true);

        try {
            JetStream js = conn.jetStream();
            DeliverPolicy deliverPolicy;
            long startSeq = startJetsStreamSeq;
            if (startSeq > 0) {
                deliverPolicy = DeliverPolicy.ByStartSequence;
            } else {
                deliverPolicy = DeliverPolicy.All;
                startSeq = 1;
            }

            ConsumerConfiguration.Builder ccb = ConsumerConfiguration.builder()
                .durable("blp-follower-" + podName.replaceAll("[^a-zA-Z0-9-]", "-"))
                .deliverPolicy(deliverPolicy)
                .ackPolicy(AckPolicy.None)
                .filterSubject(NatsJournalReplicator.SUBJECT);
            if (deliverPolicy == DeliverPolicy.ByStartSequence) {
                ccb.startSequence(startSeq);
            }

            PushSubscribeOptions opts = PushSubscribeOptions.builder()
                .configuration(ccb.build())
                .build();
            subscription = js.subscribe(NatsJournalReplicator.SUBJECT, opts);
            log.info("Follower subscribed to {} from JetStream seq={} ackMode={}",
                NatsJournalReplicator.SUBJECT, startSeq, ackMode.name().toLowerCase());
        } catch (Exception ex) {
            log.warn("Could not subscribe to replication stream: {} — continuing without replication", ex.getMessage());
            readModel.setReplaying(false);
            readinessCallback.run();
            return;
        }

        if (ackMode == AckMode.DURABLE) {
            startDurableAckPublisher();
        }

        // Drain catch-up messages (fast loop with short timeout to detect tail).
        drainCatchUp();
        readModel.setReplaying(false);
        log.info("Follower caught up at JetStream seq={} — signalling readiness", lastJetsStreamSeq.get());
        readinessCallback.run();

        // Continue tailing in daemon thread.
        followerThread = new Thread(this::tailLoop, "blp-replication-follower");
        followerThread.setDaemon(true);
        followerThread.start();
    }

    public void stop() {
        running = false;
        if (followerThread != null) followerThread.interrupt();
        try {
            if (subscription != null) subscription.unsubscribe();
        } catch (Exception ex) {
            log.warn("Unsubscribe error: {}", ex.getMessage());
        }
        Thread ackThread = durableAckThread;
        if (ackThread != null) {
            try {
                ackThread.join(5_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ----- internal -----------------------------------------------------------------------

    private void drainCatchUp() {
        while (running) {
            try {
                Message msg = subscription.nextMessage(CATCH_UP_TIMEOUT);
                if (msg == null) break;  // no more pending messages → caught up
                inject(msg);
            } catch (Exception ex) {
                if (!running) break;
                log.warn("Catch-up receive error: {}", ex.getMessage());
                break;
            }
        }
    }

    private void tailLoop() {
        while (running) {
            try {
                Message msg = subscription.nextMessage(POLL_TIMEOUT);
                if (msg != null) inject(msg);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                if (!running) break;
                log.warn("Replication receive error: {}", ex.getMessage());
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void inject(Message msg) {
        byte[] data = msg.getData();
        if (data == null || data.length < NatsJournalReplicator.RECORD_BYTES) {
            return;
        }
        RingBuffer<InputEvent> ring = this.inputRing;
        if (ring == null) return;

        // Extract the Disruptor ring sequence embedded by the primary before injecting,
        // so we can ACK it after the event is on the ring (before BLP processes it, matching
        // the LMAX model where standby receives the event at the same wall-clock time).
        long disruptorSeq = NatsJournalReplicator.decodeDisruptorSeq(data);

        long seq;
        try {
            seq = ring.next();
        } catch (Exception ex) {
            log.warn("Ring claim failed: {}", ex.getMessage());
            return;
        }
        try {
            InputEvent e = ring.get(seq);
            NatsJournalReplicator.decode(data, e);
        } finally {
            ring.publish(seq);
        }
        lastJetsStreamSeq.set(msg.metaData().streamSequence());

        if (disruptorSeq >= 0) {
            if (ackMode == AckMode.DURABLE) {
                enqueueDurableAck(seq, disruptorSeq);
            } else {
                // Historical/default behavior: ACK immediately after ring publish. Deliberately
                // unchanged until the user chooses the stronger RPO=0 policy.
                publishAck(disruptorSeq, false);
            }
        }
    }

    private void enqueueDurableAck(long localSequence, long primarySequence) {
        long cursor = durableNextWriteCursor++;
        while (cursor - durableConsumedCursor >= DURABLE_ACK_QUEUE_CAPACITY) {
            if (!running) {
                return;
            }
            Thread.onSpinWait();
        }
        int slot = (int) cursor & DURABLE_ACK_QUEUE_MASK;
        durableLocalSequences[slot] = localSequence;
        durablePrimarySequences[slot] = primarySequence;
        // Volatile publication after both array writes makes the pair visible to the ACK thread.
        durablePublishedCursor = cursor;
    }

    private void startDurableAckPublisher() {
        durableAckThread = new Thread(this::durableAckLoop, "blp-replication-durable-ack");
        durableAckThread.setDaemon(true);
        durableAckThread.start();
    }

    private void durableAckLoop() {
        long consumed = durableConsumedCursor;
        while (running || consumed < durablePublishedCursor) {
            long available = durablePublishedCursor;
            RingBuffer<InputEvent> ring = inputRing;
            if (ring == null || consumed >= available) {
                LockSupport.parkNanos(100_000L);
                continue;
            }

            // The final consumer is gated behind Journaler, whose journaledSeq is advanced only
            // after end-of-batch FileChannel.force(false). Therefore the ring's minimum gating
            // sequence is a conservative durable watermark. In the current topology it is also
            // BLP-applied; exposing the narrower post-journal/pre-BLP sequence would require the
            // LmaxEngine/Journaler wiring owned by another lane.
            long durableLocalSequence = ring.getMinimumGatingSequence();
            long candidateCursor = consumed;
            long candidatePrimarySequence = -1L;
            while (candidateCursor < available) {
                long next = candidateCursor + 1L;
                int slot = (int) next & DURABLE_ACK_QUEUE_MASK;
                if (durableLocalSequences[slot] > durableLocalSequence) {
                    break;
                }
                candidatePrimarySequence = durablePrimarySequences[slot];
                candidateCursor = next;
            }

            if (candidatePrimarySequence >= 0L) {
                if (publishAck(candidatePrimarySequence, true)) {
                    consumed = candidateCursor;
                    durableConsumedCursor = consumed;
                } else {
                    LockSupport.parkNanos(1_000_000L);
                }
            } else {
                LockSupport.parkNanos(100_000L);
            }
        }
    }

    private boolean publishAck(long disruptorSeq, boolean flush) {
        try {
            ackBuf.clear();
            ackBuf.putLong(disruptorSeq);
            conn.publish(NatsJournalReplicator.ACK_SUBJECT, ackBuf.array());
            if (flush) {
                // PING/PONG orders the reusable ACK buffer behind the server's processing of this
                // publish. It also makes the stronger watermark visible promptly to the primary.
                conn.flush(ACK_FLUSH_TIMEOUT);
            }
            return true;
        } catch (Exception ex) {
            log.warn("ACK publish failed at disruptorSeq {}: {}", disruptorSeq, ex.getMessage());
            return false;
        }
    }
}
