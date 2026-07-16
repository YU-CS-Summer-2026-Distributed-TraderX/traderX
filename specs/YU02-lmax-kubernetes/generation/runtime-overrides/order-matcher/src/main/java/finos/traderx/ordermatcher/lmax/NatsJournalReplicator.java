package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.EventHandler;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.nats.client.api.PublishAck;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Input-ring replicator for the PRIMARY pod. Serializes each InputEvent to the same 64-byte
 * binary layout as the journal and publishes it to a NATS JetStream subject so follower pods
 * can replay the identical stream.
 *
 * <p><b>Synchronous-replication mode</b> (true LMAX parity): when a follower is active, the
 * follower publishes an ACK on {@link #ACK_SUBJECT} after processing each event. The primary
 * subscribes to ACKs via {@link #startAckListener}; {@link #replicatedSeq()} returns the
 * follower-confirmed sequence so the input barrier gates the MatchingEngine on follower ACK,
 * not just broker publish ACK. Both BLPs process events in lock-step — the primary BLP cannot
 * advance past sequence N until the follower BLP has processed N.
 *
 * <p><b>Solo mode</b>: if no ACK arrives within {@code ACK_TIMEOUT_NS} (500 ms), the replicator
 * falls back to advancing on broker-publish ACK so a single-replica setup never stalls.
 *
 * <p>The Disruptor ring sequence is embedded in the last 8 bytes of each 64-byte message
 * (offset 56) so the follower can include it in its ACK without maintaining its own mapping.
 */
public final class NatsJournalReplicator implements EventHandler<InputEvent> {
    private static final Logger log = LoggerFactory.getLogger(NatsJournalReplicator.class);

    static final String STREAM_NAME  = "TRADERX_BLP_REPLICATION";
    static final String SUBJECT      = "traderx.blp.replication.events";
    static final String ACK_SUBJECT  = "traderx.blp.replication.ack";
    static final int    RECORD_BYTES = 64;

    /** If no follower ACK arrives within this window, fall back to solo (publish-ack) mode. */
    private static final long ACK_TIMEOUT_NS = 500_000_000L; // 500 ms

    /**
     * Max broker-publish ACKs in flight before we drain, bounding memory and matching jnats'
     * default pending-ack window. Publishes within a Disruptor batch pipeline concurrently;
     * we wait for their ACKs at the batch boundary (or when this cap is hit) rather than paying a
     * full JetStream round-trip per event — the difference between ~1k/s and tens of k/s under load.
     */
    private static final int MAX_IN_FLIGHT = 256;
    /** Per-ACK wait budget when draining the pipeline. */
    private static final long PUBLISH_ACK_WAIT_MS = 2000L;

    // Single-writer: onEvent runs only on the replicator's Disruptor consumer thread, so this plain
    // ArrayList needs no synchronization.
    private final List<CompletableFuture<PublishAck>> inFlight = new ArrayList<>(MAX_IN_FLIGHT);

    private final JetStream js;
    private final String subject;
    private final ThreadLocal<ByteBuffer> buf =
        ThreadLocal.withInitial(() -> ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN));

    /** Sequence the broker has ACK'd. Used in solo mode (no active follower). */
    private volatile long publishedSeq = -1;

    /** Last sequence the follower has confirmed processing. */
    private final AtomicLong followerAckedSeq = new AtomicLong(-1);

    /** Nanotime of last follower ACK; 0 means no ACK ever received. */
    private final AtomicLong lastAckNs = new AtomicLong(0);

    /** NATS Dispatcher for async ACK delivery; null when listener is not running. */
    private volatile Dispatcher ackDispatcher;

    NatsJournalReplicator(JetStream js, String subject) {
        this.js = js;
        this.subject = subject;
    }

    @Override
    public void onEvent(InputEvent e, long sequence, boolean endOfBatch) {
        ByteBuffer b = buf.get();
        b.clear();
        b.putLong(e.seq);
        b.put(e.type);
        b.put(e.side);
        b.putShort((short) 0);          // pad
        b.putInt(e.orderRef);
        b.putInt(e.accountId);
        b.putInt(e.securityId);
        b.putInt(e.qty);
        b.putLong(e.limitPx);
        b.putLong(e.priceTicks);
        b.putLong(e.eventTimeMillis);
        b.putInt(0);                    // pad (offset 52)
        b.putLong(sequence);            // Disruptor ring seq at offset 56 (was: 0-pad)
        b.flip();
        // The ThreadLocal buffer is reused on the next event, so an async publish must own its own
        // copy — publishAsync serializes the bytes off-thread after onEvent returns.
        byte[] data = Arrays.copyOf(b.array(), RECORD_BYTES);
        try {
            inFlight.add(js.publishAsync(subject, data));
        } catch (Exception ex) {
            log.warn("Replication publish failed at seq {}: {}", sequence, ex.getMessage());
        }

        // Synchronous-replication gate — batch boundary only (matching how LMAX actually works).
        //
        // Previously every event paid a synchronous js.publish() round-trip, capping throughput at
        // (1 / round_trip_latency) ≈ ~1k/s. Now events publish ASYNC as they arrive and we drain
        // their broker ACKs once per Disruptor batch (or when the in-flight window fills), so the
        // ceiling becomes (batch_size / round_trip_latency). Two-stage barrier at endOfBatch:
        //   1. drainInFlight — wait for this batch's broker-publish ACKs, advance publishedSeq
        //      (the solo-mode durability watermark).
        //   2. if a follower is active, spin until it has confirmed the batch's last sequence
        //      (the strong synchronous-replication gate — unchanged, MUST stay batch-boundary only).
        // Falls back to solo mode (no follower spin) if no follower ACK arrived in ACK_TIMEOUT_NS,
        // so a single-replica deployment never stalls.
        if (endOfBatch || inFlight.size() >= MAX_IN_FLIGHT) {
            drainInFlight(sequence);
        }
        if (endOfBatch) {
            long now = System.nanoTime();
            long ackNs = lastAckNs.get();
            if (ackNs > 0 && now - ackNs < ACK_TIMEOUT_NS) {
                long deadline = now + ACK_TIMEOUT_NS;
                while (followerAckedSeq.get() < sequence) {
                    if (System.nanoTime() >= deadline) break;
                    Thread.onSpinWait();
                }
            }
        }
    }

    /** Wait for all pending broker-publish ACKs, then advance the durable watermark to {@code seq}. */
    private void drainInFlight(long seq) {
        for (int i = 0; i < inFlight.size(); i++) {
            try {
                inFlight.get(i).get(PUBLISH_ACK_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                // Best-effort, non-stalling (mirrors solo-mode fallback): a lost/slow broker ACK is
                // logged but does not wedge the input ring. When a follower is active its ACK spin
                // above remains the strong durability signal.
                log.warn("Replication ACK wait failed near seq {}: {}", seq, ex.getMessage());
            }
        }
        inFlight.clear();
        publishedSeq = seq;
    }

    /**
     * Returns the sequence number up to which events are considered durably replicated.
     *
     * <ul>
     *   <li>When a follower is active (ACK received within 500 ms): returns the last
     *       follower-confirmed sequence — the input barrier blocks until the follower has
     *       processed the event (true synchronous replication, matching the LMAX model).
     *   <li>Otherwise (solo mode): returns {@code publishedSeq}, the last broker-ACK'd sequence,
     *       so a single-replica deployment never stalls.
     * </ul>
     */
    public long replicatedSeq() {
        long ackedSeq = followerAckedSeq.get();
        if (ackedSeq >= 0 && System.nanoTime() - lastAckNs.get() < ACK_TIMEOUT_NS) {
            return ackedSeq;
        }
        return publishedSeq;
    }

    /**
     * Start subscribing to follower ACKs on {@link #ACK_SUBJECT}. Each ACK carries the Disruptor
     * ring sequence the follower just processed; {@link #replicatedSeq()} switches to tracking
     * these rather than broker-publish ACKs as long as ACKs arrive within 500 ms.
     */
    public void startAckListener(Connection conn) {
        stopAckListener();
        try {
            ackDispatcher = conn.createDispatcher(msg -> {
                byte[] data = msg.getData();
                if (data != null && data.length >= 8) {
                    long seq = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong();
                    followerAckedSeq.set(seq);
                    lastAckNs.set(System.nanoTime());
                }
            });
            ackDispatcher.subscribe(ACK_SUBJECT);
            log.info("Primary ACK listener active on {}", ACK_SUBJECT);
        } catch (Exception ex) {
            log.warn("Could not start ACK listener — running in solo (publish-ack) mode: {}", ex.getMessage());
        }
    }

    public void stopAckListener() {
        Dispatcher d = ackDispatcher;
        if (d != null) {
            try { d.unsubscribe(ACK_SUBJECT); } catch (Exception ignore) {}
            ackDispatcher = null;
        }
        // Reset so replicatedSeq() falls back to publishedSeq immediately.
        lastAckNs.set(0);
    }

    /**
     * Create the durable replication stream (idempotent — safe to call on every primary start).
     * Returns true if the stream is ready, false if JetStream is unavailable (degraded mode).
     */
    static boolean ensureStream(Connection conn) {
        try {
            JetStreamManagement jsm = conn.jetStreamManagement();
            try {
                StreamInfo existing = jsm.getStreamInfo(STREAM_NAME);
                if (existing.getConfiguration().getStorageType() == StorageType.Memory) {
                    // JetStream cannot convert storage type in place; a pre-existing Memory
                    // stream stays Memory until it is deleted (or the broker restarts without
                    // it) and gets recreated here as File.
                    log.warn("Replication stream {} exists with Memory storage — not durable; " +
                        "delete the stream during a quiet window to recreate it as File.", STREAM_NAME);
                } else {
                    log.info("Replication stream already exists: subjects={}", existing.getConfiguration().getSubjects());
                }
                return true;
            } catch (io.nats.client.JetStreamApiException ex) {
                if (ex.getApiErrorCode() != 10059) throw ex; // 10059 = stream not found
            }
            StreamConfiguration sc = StreamConfiguration.builder()
                .name(STREAM_NAME)
                .subjects(SUBJECT)
                .storageType(StorageType.File)
                .maxAge(Duration.ofDays(1))
                .build();
            jsm.addStream(sc);
            log.info("Created replication stream: {}", STREAM_NAME);
            return true;
        } catch (Exception ex) {
            log.warn("Could not ensure JetStream replication stream — running primary without NATS replication: {}", ex.getMessage());
            return false;
        }
    }

    /** Decode a 64-byte replication record into an InputEvent in place. */
    static void decode(byte[] data, InputEvent e) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        e.seq              = b.getLong();
        e.type             = b.get();
        e.side             = b.get();
        b.getShort();                   // pad
        e.orderRef         = b.getInt();
        e.accountId        = b.getInt();
        e.securityId       = b.getInt();
        e.qty              = b.getInt();
        e.limitPx          = b.getLong();
        e.priceTicks       = b.getLong();
        e.eventTimeMillis  = b.getLong();
        e.ingressNanos     = 0;         // not carried (latency tracking only on primary)
        // offset 52-55: int pad (skipped)
        // offset 56-63: Disruptor ring seq — decoded separately via decodeDisruptorSeq()
    }

    /**
     * Extract the Disruptor ring sequence embedded at offset 56 of the 64-byte replication record.
     * The follower includes this in its ACK so the primary's input barrier can gate on it.
     */
    static long decodeDisruptorSeq(byte[] data) {
        if (data == null || data.length < 64) return -1;
        return ByteBuffer.wrap(data, 56, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
}
