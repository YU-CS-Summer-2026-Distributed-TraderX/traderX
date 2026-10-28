package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-092 (FR-FGM-05): with no NATS, the FIX fill feed retries with backoff to 60 s and says so once, with a
 * counter. f15ho ran an hour against a NATS host that did not exist: one attempt every 2 s, each logged by jnats
 * as two SEVERE lines (900–2,200 per gateway). Measured locally, an attempt costs ~1 ms, no thread and no
 * retained heap, so the loop was noise, not load.
 */
class FillFeedRetryTest {
    private final PrintStream err = System.err;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @BeforeEach
    void reset() {
        ClusterGatewayMain.fillFeedSubscribed = false;
        ClusterGatewayMain.fillFeedConnectFailures.set(0);
        System.setErr(new PrintStream(captured, true));
    }

    @AfterEach
    void restore() {
        System.setErr(err);
        ClusterGatewayMain.fillFeedSubscribed = false;
    }

    @Test
    void anUnreachableFeedBacksOffToAMinuteAndWarnsOnce() {
        final List<Long> sleeps = new ArrayList<>();
        assertThrows(InterruptedException.class, () -> ClusterGatewayMain.retryFillFeed("nats://nowhere:4222",
            () -> { throw new java.io.IOException("Unable to connect to NATS servers"); },
            ms -> {
                sleeps.add(ms);
                if (sleeps.size() == 10) {
                    throw new InterruptedException("test over");
                }
            }));
        assertEquals(List.of(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L, 60_000L), sleeps);
        assertEquals(10, ClusterGatewayMain.fillFeedConnectFailures.get(), "every failed attempt counted");
        assertEquals(1, captured.toString().lines().filter(l -> l.contains("FIX fill feed WARN")).count(),
            "one WARN for the outage, not one per attempt:\n" + captured);
        assertEquals(false, ClusterGatewayMain.fillFeedSubscribed);
    }

    @Test
    void aFeedThatComesUpIsSubscribedAndReported() throws Exception {
        final AtomicInteger attempts = new AtomicInteger();
        ClusterGatewayMain.retryFillFeed("nats://later:4222", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new java.io.IOException("not yet");
            }
            return null;
        }, ms -> { });
        assertTrue(ClusterGatewayMain.fillFeedSubscribed);
        assertEquals(2, ClusterGatewayMain.fillFeedConnectFailures.get());
    }

    /** jnats's own logger, through java.util.logging: before ADR-092, two SEVERE records per failed attempt. */
    @Test
    void aFailedConnectAttemptLogsNothingOfItsOwn() {
        final AtomicInteger records = new AtomicInteger();
        final Handler count = new Handler() {
            @Override
            public void publish(final LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                    records.incrementAndGet();
                }
            }

            @Override public void flush() { }
            @Override public void close() { }
        };
        final Logger root = Logger.getLogger("");
        root.addHandler(count);
        try {
            for (int i = 0; i < 5; i++) {
                assertThrows(Exception.class, () -> ClusterGatewayMain.connectFillFeed("nats://nats.invalid:4222", "auto",
                    "TRADERX_FEED", b -> { }));
            }
        } finally {
            root.removeHandler(count);
        }
        assertEquals(0, records.get(), "jnats logged its own record for each failed attempt");
    }
}
