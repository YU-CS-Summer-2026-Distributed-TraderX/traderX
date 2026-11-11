package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import quickfix.MessageStore;
import quickfix.SessionID;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-099 measurement: what one gateway's ADR-086 store sustains, as QuickFIX/J drives it. Each session is one
 * thread doing, per order, what QuickFIX/J does under its session lock: the inbound number moves
 * (incrNextTargetMsgSeqNum), and the answer is stored before it is sent (set + incrNextSenderMsgSeqNum).
 * FIX_STORE_BENCH=1 runs it; FIX_STORE_BENCH_SESSIONS (33, f15's per-gateway count) and _SECONDS (10) size it.
 * FIX_STORE_BENCH_GATEWAYS (1) splits the sessions over that many store clients, each with its own NATS connection, as
 * a venue's gateways share the store (the 2026-11-10 acceptance run: 600 sessions on 18 gateways).
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "FIX_STORE_BENCH", matches = "1")
class FixStoreThroughputIT {
    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine")
        .withCommand("-js")
        .withExposedPorts(4222)
        .waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    private static final String REPORT = "8=FIX.4.4\u00019=240\u000135=8\u000134=%d\u000149=TRADERX\u000156=C%d\u0001"
        + "37=ord-1\u000111=c1\u000117=x-1\u0001150=0\u000139=0\u000155=IBM\u000154=1\u000138=10\u000144=100\u0001"
        + "151=10\u000114=0\u00016=0\u000160=20261030-12:00:00.000\u000110=000\u0001";

    @Test
    void ordersPerSecondThroughOneGatewaysStore() throws Exception {
        final int sessions = Integer.parseInt(System.getenv().getOrDefault("FIX_STORE_BENCH_SESSIONS", "33"));
        final int seconds = Integer.parseInt(System.getenv().getOrDefault("FIX_STORE_BENCH_SECONDS", "10"));
        final int gateways = Integer.parseInt(System.getenv().getOrDefault("FIX_STORE_BENCH_GATEWAYS", "1"));
        final List<FixSessionStore> gws = new ArrayList<>();
        for (int g = 0; g < gateways; g++) {
            final FixSessionStore store = new FixSessionStore(
                "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222), "gw-bench-" + g);
            for (int i = 0; i < 100 && !store.available(); i++) {
                Thread.sleep(100);
            }
            assertTrue(store.available());
            gws.add(store);
        }
        final List<MessageStore> stores = new ArrayList<>();
        for (int s = 0; s < sessions; s++) {
            final FixSessionStore store = gws.get(s % gateways);
            final SessionID sid = new SessionID("FIX.4.4", "TRADERX", "C" + s);
            stores.add(store.create(sid));
            assertTrue(store.acquire(sid));
            store.live(sid, true);
        }
        final AtomicBoolean measuring = new AtomicBoolean();
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicLong orders = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final CountDownLatch done = new CountDownLatch(sessions);
        for (int s = 0; s < sessions; s++) {
            final int session = s;
            final MessageStore ms = stores.get(s);
            final Thread t = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        ms.incrNextTargetMsgSeqNum();
                        final int n = ms.getNextSenderMsgSeqNum();
                        ms.set(n, String.format(REPORT, n, session));
                        ms.incrNextSenderMsgSeqNum();
                        if (measuring.get()) {
                            orders.incrementAndGet();
                        }
                    }
                } catch (final Exception e) {
                    failures.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }, "session-" + s);
            t.start();
        }
        Thread.sleep(2_000);   // warm-up
        measuring.set(true);
        final long t0 = System.nanoTime();
        Thread.sleep(seconds * 1_000L);
        final long n = orders.get();
        final double secs = (System.nanoTime() - t0) / 1e9;
        stop.set(true);
        done.await();
        final String commits = gws.get(0).commitMetrics().replace('\n', ' ');
        gws.forEach(FixSessionStore::close);
        System.out.printf("[fix-store-bench] buckets=%s gateways=%d sessions=%d orders/s=%.0f per-session/s=%.0f"
            + " us/order/session=%.0f | gateway 0: %s%n", System.getenv().getOrDefault("FIX_SESSION_BUCKETS", "1"),
            gateways, sessions, n / secs, n / secs / sessions, 1e6 * secs * sessions / n, commits);
        assertTrue(failures.get() == 0, failures.get() + " session thread(s) failed");
    }
}
