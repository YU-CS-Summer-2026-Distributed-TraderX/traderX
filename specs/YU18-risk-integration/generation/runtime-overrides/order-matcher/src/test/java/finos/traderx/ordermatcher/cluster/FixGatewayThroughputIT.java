package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-099 measurement end to end: one gateway (acceptor + ADR-086 store) answering N raw FIX clients that keep
 * FIX_GW_BENCH_WINDOW orders outstanding each. The venue answers at once, so the store and QuickFIX/J are what is
 * measured. FIX_GW_BENCH=1 runs it; FIX_STORE_GROUP_COMMIT=false measures the synchronous store in the same build.
 * FIX_GW_BENCH_NATS_DELAY_MS adds that much latency each way between the gateway and NATS (an in-process proxy), and
 * FIX_GW_BENCH_VENUE_MS answers each order that long after its submit (the cluster round trip), so a step that waits
 * on either once per order shows as a per-session rate near 1 / that delay. FIX_GW_BENCH_VENUE_WINDOW bounds the orders
 * the venue holds at once, taken on the submitting thread as ClusterGatewayMain's GATEWAY_MAX_INFLIGHT is.
 * FIX_GW_BENCH_FILLS=1 fills every order: one feed thread (the gateway's NATS consumer) delivers its FILLED update
 * just before its answer, as the leader publishes both from one apply. FIX_GW_BENCH_GATEWAYS runs that many gateways
 * (each with FIX_GW_BENCH_SESSIONS sessions and its own store client) on the one NATS server, as a venue's gateways
 * share one FIX_SESSIONS bucket.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "FIX_GW_BENCH", matches = "1")
class FixGatewayThroughputIT {
    @Container
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.14-alpine")
        .withCommand("-js").withExposedPorts(4222).waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS");

    private static final class Venue implements OrderSubmitter {
        final AtomicInteger ref = new AtomicInteger(1);
        final long venueMs = Long.parseLong(System.getenv().getOrDefault("FIX_GW_BENCH_VENUE_MS", "0"));
        final java.util.concurrent.ScheduledExecutorService later =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        final int windowSize = Integer.parseInt(System.getenv().getOrDefault("FIX_GW_BENCH_VENUE_WINDOW", "0"));
        final boolean fills = "1".equals(System.getenv("FIX_GW_BENCH_FILLS"));
        final java.util.concurrent.ExecutorService feed = java.util.concurrent.Executors.newSingleThreadExecutor();
        final OrderNatsPublisher encoder = new OrderNatsPublisher("", "/orders", "7", null, 16, 1);
        volatile FixGatewayAcceptor gw;

        void fill(final int ref) {
            if (!fills) {
                return;
            }
            final OrderNatsPublisher.Rec r = new OrderNatsPublisher.Rec();
            r.orderRef = ref;
            r.accountId = 11;
            r.security = "IBM";
            r.side = 1;
            r.quantity = 10;
            r.remainingQty = 0;
            r.limitPx = 100_000_000L;
            r.status = 2;   // FILLED
            r.lastExecPx = 100_000_000L;
            r.lastFillQty = 10;
            final byte[] json = encoder.encode(new StringBuilder(1024), r);
            feed.execute(() -> gw.onOrderUpdate(json));
        }
        final Semaphore window = windowSize > 0 ? new Semaphore(windowSize) : null;

        @Override
        public java.util.concurrent.CompletableFuture<ExecResult> submitOrderAsync(
                final String clOrdId, final int accountId, final String ticker, final char side, final int qty,
                final long limitPxTicks, final TypedOrder typed, final java.util.concurrent.Executor completions) {
            if (window != null) {
                window.acquireUninterruptibly();
            }
            final ExecResult r = new ExecResult(true, ref.getAndIncrement(), OutputEvent.KIND_ORDER_ACCEPTED);
            fill(r.orderRef());
            if (venueMs == 0) {
                if (window != null) {
                    window.release();
                }
                return java.util.concurrent.CompletableFuture.completedFuture(r);
            }
            final java.util.concurrent.CompletableFuture<ExecResult> f = new java.util.concurrent.CompletableFuture<>();
            later.schedule(() -> {
                if (window != null) {
                    window.release();
                }
                f.completeAsync(() -> r, completions);
            }, venueMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return f;
        }

        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker, final char side,
                                      final int qty, final long limitPxTicks) {
            return new ExecResult(true, ref.getAndIncrement(), OutputEvent.KIND_ORDER_ACCEPTED);
        }

        /** FIX_EXACTLY_ONCE=jetstream (ADR-090, FR-XOD-12): the sequenced submit waits for its answer on the session's
         *  thread, so the venue's delay is paid in line here, as the pipelined path pays it on {@link #later}. */
        @Override
        public ExecResult submitSequencedOrder(final String clOrdId, final int accountId, final String ticker,
                                               final char side, final int qty, final long limitPxTicks,
                                               final TypedOrder typed, final long streamId, final long streamSeq) {
            if (venueMs > 0) {
                try {
                    Thread.sleep(venueMs);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            final ExecResult r = new ExecResult(true, ref.getAndIncrement(), OutputEvent.KIND_ORDER_ACCEPTED);
            fill(r.orderRef());
            return r;
        }

        @Override
        public ExecResult submitCancel(final int orderRef, final int accountId) {
            return new ExecResult(true, orderRef, OutputEvent.KIND_ORDER_CANCELED);
        }
    }

    private static void send(final OutputStream os, final String compId, final int seq, final String type,
                             final String body) throws Exception {
        final String b = "35=" + type + "\u000149=" + compId + "\u000156=TRADERX\u000134=" + seq + "\u000152="
            + ZonedDateTime.now(ZoneOffset.UTC).format(TS) + "\u0001" + body + "\u0001";
        final String head = "8=FIX.4.4\u00019=" + b.length() + "\u0001";
        int sum = 0;
        for (final byte x : (head + b).getBytes(StandardCharsets.US_ASCII)) {
            sum += x & 0xff;
        }
        os.write((head + b + String.format("10=%03d\u0001", sum % 256)).getBytes(StandardCharsets.US_ASCII));
    }

    /** A TCP proxy that holds every chunk {@code delayMs} before passing it on, in each direction; its local port. */
    private static int delayProxy(final String host, final int port, final long delayMs) throws Exception {
        final java.net.ServerSocket server = new java.net.ServerSocket(0);
        final Thread accept = new Thread(() -> {
            try {
                while (true) {
                    final Socket in = server.accept();
                    final Socket out = new Socket(host, port);
                    in.setTcpNoDelay(true);
                    out.setTcpNoDelay(true);
                    pipe(in, out, delayMs);
                    pipe(out, in, delayMs);
                }
            } catch (final Exception e) {
                // closed
            }
        }, "nats-delay-accept");
        accept.setDaemon(true);
        accept.start();
        return server.getLocalPort();
    }

    private static void pipe(final Socket from, final Socket to, final long delayMs) {
        final java.util.concurrent.LinkedBlockingQueue<Object[]> q = new java.util.concurrent.LinkedBlockingQueue<>();
        final Thread read = new Thread(() -> {
            try {
                final byte[] buf = new byte[1 << 16];
                for (int n; (n = from.getInputStream().read(buf)) > 0; ) {
                    q.put(new Object[] {System.nanoTime() + delayMs * 1_000_000L, java.util.Arrays.copyOf(buf, n)});
                }
            } catch (final Exception e) {
                // closed
            }
        }, "nats-delay-read");
        final Thread write = new Thread(() -> {
            try {
                final OutputStream os = to.getOutputStream();
                while (true) {
                    final Object[] c = q.take();
                    final long wait = (long) c[0] - System.nanoTime();
                    if (wait > 0) {
                        java.util.concurrent.locks.LockSupport.parkNanos(wait);
                    }
                    os.write((byte[]) c[1]);
                }
            } catch (final Exception e) {
                // closed
            }
        }, "nats-delay-write");
        read.setDaemon(true);
        write.setDaemon(true);
        read.start();
        write.start();
    }

    @Test
    void executionReportsPerSecondThroughOneGateway() throws Exception {
        final int sessions = Integer.parseInt(System.getenv().getOrDefault("FIX_GW_BENCH_SESSIONS", "33"));
        final int window = Integer.parseInt(System.getenv().getOrDefault("FIX_GW_BENCH_WINDOW", "64"));
        final int seconds = Integer.parseInt(System.getenv().getOrDefault("FIX_GW_BENCH_SECONDS", "10"));
        final int gateways = Integer.parseInt(System.getenv().getOrDefault("FIX_GW_BENCH_GATEWAYS", "1"));
        final long natsDelayMs = Long.parseLong(System.getenv().getOrDefault("FIX_GW_BENCH_NATS_DELAY_MS", "0"));
        final int natsPort = natsDelayMs == 0 ? NATS.getMappedPort(4222)
            : delayProxy(NATS.getHost(), NATS.getMappedPort(4222), natsDelayMs);
        final List<FixSessionStore> stores = new ArrayList<>();
        final List<FixGatewayAcceptor> gws = new ArrayList<>();
        final List<Object[]> clients = new ArrayList<>();   // {compId, port}
        for (int g = 0; g < gateways; g++) {
            final List<String> compIds = new ArrayList<>();
            for (int s = 0; s < sessions; s++) {
                compIds.add("G" + g + "C" + s);
            }
            final FixSessionStore store = new FixSessionStore("nats://" + NATS.getHost() + ":" + natsPort, "gw-bench-" + g);
            for (int i = 0; i < 100 && !store.available(); i++) {
                Thread.sleep(100);
            }
            final int port;
            try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
                port = s.getLocalPort();
            }
            final Venue venue = new Venue();
            final FixGatewayAcceptor gw = new FixGatewayAcceptor(venue, null, port, "TRADERX", 11, compIds)
                .withSessionStore(store);
            venue.gw = gw;
            gw.start();
            stores.add(store);
            gws.add(gw);
            compIds.forEach(c -> clients.add(new Object[] {c, port}));
        }
        final FixSessionStore store = stores.get(0);
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicBoolean measuring = new AtomicBoolean();
        final AtomicLong reports = new AtomicLong();
        final AtomicLong tradeReports = new AtomicLong();
        final AtomicInteger failed = new AtomicInteger();
        final List<Thread> threads = new ArrayList<>();
        // NFR-XOD-03: order-to-ack, each order stamped when its bytes leave the client (the writer flushes when its window
        // is spent) and paired with the session's next ExecType=0: exact here, the venue answers a session in order.
        final java.util.concurrent.ConcurrentLinkedQueue<Long> latencies = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (final Object[] client : clients) {
            final java.util.concurrent.ConcurrentLinkedQueue<Long> sentAt = new java.util.concurrent.ConcurrentLinkedQueue<>();
            final String compId = (String) client[0];
            final Socket socket = new Socket("127.0.0.1", (int) client[1]);
            final OutputStream os = new java.io.BufferedOutputStream(socket.getOutputStream(), 1 << 16);
            final InputStream in = socket.getInputStream();
            final Semaphore outstanding = new Semaphore(window);
            send(os, compId, 1, "A", "98=0\u0001108=30\u0001141=Y");
            os.flush();
            final Thread reader = new Thread(() -> {
                final byte[] pattern = "\u0001150=".getBytes(StandardCharsets.US_ASCII);
                final byte[] buf = new byte[1 << 16];
                int matched = 0;
                boolean typeNext = false;
                try {
                    for (int n; !stop.get() && (n = in.read(buf)) > 0; ) {
                        for (int i = 0; i < n; i++) {   // ExecType(150) across reads: 0 answers an order, F is its fill
                            if (typeNext) {
                                typeNext = false;
                                if (buf[i] == '0') {
                                    outstanding.release();
                                    final Long at = sentAt.poll();
                                    if (measuring.get()) {
                                        reports.incrementAndGet();
                                        if (at != null) {
                                            latencies.add(System.nanoTime() - at);
                                        }
                                    }
                                } else if (buf[i] == 'F' && measuring.get()) {
                                    tradeReports.incrementAndGet();
                                }
                                continue;
                            }
                            matched = buf[i] == pattern[matched] ? matched + 1 : buf[i] == pattern[0] ? 1 : 0;
                            if (matched == pattern.length) {
                                matched = 0;
                                typeNext = true;
                            }
                        }
                    }
                } catch (final Exception e) {
                    if (!stop.get()) {
                        failed.incrementAndGet();
                    }
                }
            });
            final Thread writer = new Thread(() -> {
                try {
                    Thread.sleep(500);   // the Logon answer first
                    int unstamped = 0;
                    for (int seq = 2, id = 0; !stop.get(); seq++, id++) {
                        if (!outstanding.tryAcquire()) {
                            os.flush();
                            for (final long now = System.nanoTime(); unstamped > 0; unstamped--) {
                                sentAt.add(now);
                            }
                            outstanding.acquire();
                        }
                        unstamped++;
                        send(os, compId, seq, "D", "11=" + compId + "-" + id + "\u00011=11\u000155=IBM\u000154=2\u000160="
                            + ZonedDateTime.now(ZoneOffset.UTC).format(TS) + "\u000138=10\u000140=2\u000144=100");
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (final Exception e) {
                    if (!stop.get()) {
                        failed.incrementAndGet();
                    }
                }
            });
            reader.start();
            writer.start();
            threads.add(writer);
        }
        Thread.sleep(3_000);   // warm-up
        measuring.set(true);
        final long t0 = System.nanoTime();
        Thread.sleep(seconds * 1_000L);
        final double rate = reports.get() / ((System.nanoTime() - t0) / 1e9);
        stop.set(true);
        threads.forEach(Thread::interrupt);
        System.out.printf("[fix-gw-bench] exactlyOnce=%s groupCommit=%s gateways=%d sessions=%d window=%d natsDelayMs=%d venueMs=%s"
            + " venueWindow=%s fills=%s answers/s=%.0f per-session=%.0f fill-reports/s=%.0f lost=%d%n",
            store.sequenceOrders, store.groupCommit(),
            gateways, sessions, window, natsDelayMs, System.getenv().getOrDefault("FIX_GW_BENCH_VENUE_MS", "0"),
            System.getenv().getOrDefault("FIX_GW_BENCH_VENUE_WINDOW", "0"), System.getenv("FIX_GW_BENCH_FILLS"), rate,
            rate / clients.size(), tradeReports.get() / ((System.nanoTime() - t0) / 1e9),
            gws.stream().mapToLong(FixGatewayAcceptor::fillsLost).sum());
        final long[] lat = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.printf("[fix-gw-bench] order-to-ack ms: n=%d p50=%.2f p99=%.2f max=%.2f%n", lat.length,
            lat.length == 0 ? 0 : lat[lat.length / 2] / 1e6, lat.length == 0 ? 0 : lat[(int) (lat.length * 0.99)] / 1e6,
            lat.length == 0 ? 0 : lat[lat.length - 1] / 1e6);
        System.out.print("[fix-gw-bench] gateway 0 store: " + stores.get(0).commitMetrics().replace('\n', ' ') + "\n");
        gws.forEach(FixGatewayAcceptor::stop);
        stores.forEach(FixSessionStore::close);
        assertTrue(failed.get() == 0, failed.get() + " client(s) failed");
        assertTrue(rate > 0, "nothing answered");
    }
}
