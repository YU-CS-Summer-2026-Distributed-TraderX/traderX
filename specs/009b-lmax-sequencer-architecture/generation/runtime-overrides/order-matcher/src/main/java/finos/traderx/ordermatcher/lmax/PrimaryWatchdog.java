package finos.traderx.ordermatcher.lmax;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Standby-side failure detector: probes the live node's health URL on an interval and, after N
 * consecutive failures, asks the engine to promote. The probe is only the TRIGGER — promotion
 * still has to win the {@link LeaderLock}, so a leader that is alive but slow to answer HTTP
 * (GC pause, thread-pool exhaustion) keeps the standby fenced out and the watchdog simply keeps
 * watching. Any HTTP status counts as alive; only connect/timeout failures count against the node
 * (a degraded leader is still the leader).
 */
public final class PrimaryWatchdog implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PrimaryWatchdog.class);

    private final URI watchUri;
    private final long probeIntervalMs;
    private final int failuresToPromote;
    private final BooleanSupplier promote;   // returns true once this node is live (stop watching)
    private final HttpClient client;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile int consecutiveFailures;

    public PrimaryWatchdog(String watchUrl, long probeIntervalMs, int failuresToPromote,
                           BooleanSupplier promote) {
        this.watchUri = URI.create(watchUrl);
        this.probeIntervalMs = Math.max(100, probeIntervalMs);
        this.failuresToPromote = Math.max(1, failuresToPromote);
        this.promote = promote;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(this.probeIntervalMs))
            .build();
        this.thread = new Thread(this::run, "primary-watchdog");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
        log.info("Watching primary at {} every {} ms (promote after {} consecutive failures)",
            watchUri, probeIntervalMs, failuresToPromote);
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    private void run() {
        HttpRequest request = HttpRequest.newBuilder(watchUri)
            .timeout(Duration.ofMillis(probeIntervalMs))
            .GET()
            .build();
        while (running) {
            try {
                Thread.sleep(probeIntervalMs);
            } catch (InterruptedException ex) {
                return;
            }
            if (probe(request)) {
                consecutiveFailures = 0;
                continue;
            }
            consecutiveFailures++;
            if (consecutiveFailures < failuresToPromote) {
                continue;
            }
            log.warn("Primary unreachable for {} consecutive probes; attempting promotion", consecutiveFailures);
            try {
                if (promote.getAsBoolean()) {
                    return;   // we are live now; the watchdog's job is done
                }
            } catch (RuntimeException ex) {
                log.error("Promotion attempt failed: {}", ex.toString(), ex);
            }
            // Promotion refused (leader lock still held) or failed: keep probing; a held lock
            // means the primary process is actually alive even though HTTP is not answering.
            consecutiveFailures = 0;
        }
    }

    private boolean probe(HttpRequest request) {
        try {
            client.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return true;   // shutting down; do not count as a primary failure
        } catch (Exception ex) {
            log.debug("Primary probe failed: {}", ex.toString());
            return false;
        }
    }

    @Override
    public void close() {
        running = false;
        // Promotion runs ON this thread (the probe loop calls promote), so a self-close must not
        // interrupt: a pending interrupt flag poisons every NIO channel open/read the promotion
        // does next (ClosedByInterruptException — observed disabling the promoted leader's journal).
        if (Thread.currentThread() != thread) {
            thread.interrupt();
        }
    }
}
