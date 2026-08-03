package finos.traderx.ordermatcher.cluster;

import io.nats.client.Connection;
import io.nats.client.Nats;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leader-side position-cut → NATS bridge (YU15, ADR-055) — the same shape ADR-048 established for
 * booked trades, for the same reason: the deterministic apply thread renders the cut (it is the
 * only thread allowed to read engine state) and hands it to a daemon thread, which does the I/O.
 * The state machine never blocks on NATS, not even for a batch job.
 *
 * <p>Why NATS and not cluster egress: egress is best-effort and reaches only the submitting session
 * (ADR-048), so a truncated cut would arrive looking complete. The cut is published as a single
 * message carrying its own {@code rows=} count, so the consumer can prove it received the whole
 * thing, and a dropped message is a visible timeout rather than a silently short extract.
 *
 * <p>Only the leader publishes (role check at the call site) so followers never duplicate — but
 * every member renders and hashes the cut, which is what makes cross-member byte-identity provable.
 *
 * // ponytail: one NATS message per cut. jnats' default 1MB max payload caps this at roughly 15k
 * // position rows; past that, chunk by account or write the cut straight to the object store.
 */
final class RiskExtractCutPublisher {

    private final String url;
    private final String subject;
    private final OneToOneConcurrentArrayQueue<String> queue = new OneToOneConcurrentArrayQueue<>(8);
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;
    private Thread thread;
    private volatile Connection nats;

    RiskExtractCutPublisher(final String url, final String subject) {
        this.url = url;
        this.subject = subject;
    }

    void start() {
        thread = new Thread(this::run, "risk-extract-cut-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    /** Service (apply) thread — non-blocking, never throws. */
    void offer(final String cut) {
        if (!queue.offer(cut)) {
            dropped.incrementAndGet();
        }
    }

    private void run() {
        while (running && nats == null) {
            try {
                nats = Nats.connect(url); // the client owns reconnection from here
            } catch (final Exception e) {
                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException ie) {
                    return;
                }
            }
        }
        while (running) {
            final String cut = queue.poll();
            if (cut == null) {
                try {
                    Thread.sleep(5);
                } catch (final InterruptedException e) {
                    return;
                }
                continue;
            }
            try {
                nats.publish(subject, cut.getBytes(StandardCharsets.US_ASCII));
                nats.flush(java.time.Duration.ofSeconds(5));
                published.incrementAndGet();
            } catch (final Exception e) {
                dropped.incrementAndGet();
            }
        }
    }

    long published() {
        return published.get();
    }

    long dropped() {
        return dropped.get();
    }

    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        final Connection c = nats;
        if (c != null) {
            try {
                c.close();
            } catch (final Exception ignore) {
                // shutting down
            }
        }
    }
}
