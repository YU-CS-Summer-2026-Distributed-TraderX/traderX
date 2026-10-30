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

        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker, final char side,
                                      final int qty, final long limitPxTicks) {
            return new ExecResult(true, ref.getAndIncrement(), OutputEvent.KIND_ORDER_ACCEPTED);
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

    @Test
    void executionReportsPerSecondThroughOneGateway() throws Exception {
        final int sessions = Integer.parseInt(System.getenv().getOrDefault("FIX_GW_BENCH_SESSIONS", "33"));
        final int window = Integer.parseInt(System.getenv().getOrDefault("FIX_GW_BENCH_WINDOW", "64"));
        final int seconds = Integer.parseInt(System.getenv().getOrDefault("FIX_GW_BENCH_SECONDS", "10"));
        final List<String> compIds = new ArrayList<>();
        for (int s = 0; s < sessions; s++) {
            compIds.add("C" + s);
        }
        final FixSessionStore store = new FixSessionStore(
            "nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222), "gw-bench");
        for (int i = 0; i < 100 && !store.available(); i++) {
            Thread.sleep(100);
        }
        final int port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort();
        }
        final FixGatewayAcceptor gw = new FixGatewayAcceptor(new Venue(), null, port, "TRADERX", 11, compIds)
            .withSessionStore(store);
        gw.start();
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicBoolean measuring = new AtomicBoolean();
        final AtomicLong reports = new AtomicLong();
        final AtomicInteger failed = new AtomicInteger();
        final List<Thread> threads = new ArrayList<>();
        for (final String compId : compIds) {
            final Socket socket = new Socket("127.0.0.1", port);
            final OutputStream os = new java.io.BufferedOutputStream(socket.getOutputStream(), 1 << 16);
            final InputStream in = socket.getInputStream();
            final Semaphore outstanding = new Semaphore(window);
            send(os, compId, 1, "A", "98=0\u0001108=30\u0001141=Y");
            os.flush();
            final Thread reader = new Thread(() -> {
                final byte[] pattern = "\u000135=8\u0001".getBytes(StandardCharsets.US_ASCII);
                final byte[] buf = new byte[1 << 16];
                int matched = 0;
                try {
                    for (int n; !stop.get() && (n = in.read(buf)) > 0; ) {
                        for (int i = 0; i < n; i++) {   // count "35=8" across reads
                            matched = buf[i] == pattern[matched] ? matched + 1 : buf[i] == pattern[0] ? 1 : 0;
                            if (matched == pattern.length) {
                                matched = 0;
                                outstanding.release();
                                if (measuring.get()) {
                                    reports.incrementAndGet();
                                }
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
                    for (int seq = 2, id = 0; !stop.get(); seq++, id++) {
                        if (!outstanding.tryAcquire()) {
                            os.flush();
                            outstanding.acquire();
                        }
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
        System.out.printf("[fix-gw-bench] groupCommit=%s sessions=%d window=%d execution-reports/s=%.0f%n",
            store.groupCommit(), sessions, window, rate);
        gw.stop();
        store.close();
        assertTrue(failed.get() == 0, failed.get() + " client(s) failed");
        assertTrue(rate > 0, "nothing answered");
    }
}
