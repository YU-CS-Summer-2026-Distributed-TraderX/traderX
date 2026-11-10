package finos.traderx.ordermatcher.cluster;

import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.charset.StandardCharsets;

/**
 * Diagnostic (not shipped in the runtime path): bare Aeron unicast pub/sub between two pods.
 *
 * MODE=sub: bind a Subscription to endpoint=POD_IP:PORT and print received payloads.
 * MODE=pub: create a Publication to endpoint=TARGET:PORT and offer until connected.
 * These two isolate whether cross-pod Aeron UDP works AT ALL, independent of Aeron Cluster egress.
 *
 * MODE=pong / MODE=ping (latency L0): the wire floor, Aeron UDP round trip between two pods with no
 * cluster, consensus or service in the path. pong subscribes on POD_IP:PORT and echoes each message to
 * TARGET:PORT+1; ping publishes to TARGET:PORT, subscribes on POD_IP:PORT+1, sends RATE/s for WARMUP+SECS
 * seconds on a fixed schedule and prints the RTT of the measured part (µs, from the intended send time,
 * so a stall on the ping side is counted, not hidden). IDLE=busyspin (default: the floor) | lowpark (the
 * members' CLUSTER_IDLE_STRATEGY); the driver is DEDICATED like the members'. Run it between pods on
 * DIFFERENT nodes: with hostNetwork a pod's IP is its node's, and pinging yourself reads as loopback.
 */
public final class AeronPingPong {
    public static void main(final String[] args) throws Exception {
        final String mode = env("MODE", "sub");
        final int port = Integer.parseInt(env("PORT", "40470"));
        final String aeronDir = env("PP_AERON_DIR", "/dev/shm/aeron-pp");
        final boolean rtt = "ping".equals(mode) || "pong".equals(mode);
        final IdleStrategy idle = "lowpark".equals(env("IDLE", "busyspin"))
            ? new BackoffIdleStrategy(10, 5, 1000, 1_000L) : new NoOpIdleStrategy();
        final MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(rtt ? ThreadingMode.DEDICATED : ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true);
        if (rtt) {
            driverContext.senderIdleStrategy(idle).receiverIdleStrategy(idle).conductorIdleStrategy(
                new BackoffIdleStrategy(10, 5, 1000, 1_000_000L));
        }
        final MediaDriver driver = MediaDriver.launch(driverContext);
        final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));

        if (rtt) {
            pingPong(aeron, mode, port, idle);
        } else if ("sub".equals(mode)) {
            final String host = env("POD_IP", "0.0.0.0");
            final String channel = "aeron:udp?term-length=64k|endpoint=" + host + ":" + port;
            System.out.println("SUB binding " + channel);
            final Subscription sub = aeron.addSubscription(channel, 1001);
            final long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                sub.poll((buffer, offset, length, header) -> {
                    final byte[] b = new byte[length];
                    buffer.getBytes(offset, b);
                    System.out.println("SUB-RECV " + new String(b, StandardCharsets.UTF_8));
                }, 10);
                Thread.sleep(5);
            }
        } else {
            final String target = env("TARGET", "127.0.0.1");
            final String channel = "aeron:udp?term-length=64k|endpoint=" + target + ":" + port;
            System.out.println("PUB to " + channel);
            final Publication pub = aeron.addPublication(channel, 1001);
            final UnsafeBuffer buf = new UnsafeBuffer(new byte[64]);
            final long deadline = System.currentTimeMillis() + 90_000;
            boolean announced = false;
            int n = 0;
            while (System.currentTimeMillis() < deadline) {
                if (pub.isConnected() && !announced) {
                    System.out.println("PUB-CONNECTED");
                    announced = true;
                }
                final int len = buf.putStringWithoutLengthAscii(0, "PING-" + (n++));
                pub.offer(buf, 0, len);
                Thread.sleep(200);
            }
            System.out.println(announced ? "PUB-DONE-CONNECTED" : "PUB-DONE-NEVER-CONNECTED");
        }
        aeron.close();
        driver.close();
    }

    private static void pingPong(final Aeron aeron, final String mode, final int port, final IdleStrategy idle) {
        final String self = env("POD_IP", "0.0.0.0");
        final String target = env("TARGET", "127.0.0.1");
        final boolean ping = "ping".equals(mode);
        final Subscription sub = aeron.addSubscription(
            "aeron:udp?term-length=64k|endpoint=" + self + ":" + (ping ? port + 1 : port), 1002);
        final Publication pub = aeron.addPublication(
            "aeron:udp?term-length=64k|endpoint=" + target + ":" + (ping ? port : port + 1), 1002);
        final long secs = Long.parseLong(env("SECS", "30"));
        final long warmupNs = Long.parseLong(env("WARMUP", "5")) * 1_000_000_000L;
        System.out.println("PP " + mode + " self=" + self + " target=" + target + " idle=" + env("IDLE", "busyspin"));
        while (!pub.isConnected() || !sub.isConnected()) {
            idle.idle();
        }
        if (!ping) {   // echo until the ping side goes away
            final long deadline = System.nanoTime() + (secs + 60) * 1_000_000_000L;
            final FragmentAssembler echo = new FragmentAssembler((buffer, offset, length, header) -> {
                while (pub.offer(buffer, offset, length) < 0) {
                    idle.idle();
                }
            });
            while (System.nanoTime() < deadline) {
                idle.idle(sub.poll(echo, 16));
            }
            System.out.println("PONG-DONE");
            return;
        }
        final Histogram hist = new Histogram(60_000_000_000L, 3);
        final long periodNs = 1_000_000_000L / Long.parseLong(env("RATE", "1000"));
        final long t0 = System.nanoTime();
        final long measureFrom = t0 + warmupNs;
        final long end = measureFrom + secs * 1_000_000_000L;
        final UnsafeBuffer out = new UnsafeBuffer(new byte[32]);
        final long[] lost = {0};
        final FragmentAssembler onEcho = new FragmentAssembler((buffer, offset, length, header) -> {
            final long intended = buffer.getLong(offset);
            if (intended >= measureFrom) {
                hist.recordValue(System.nanoTime() - intended);
            }
        });
        for (long intended = t0; intended < end; intended += periodNs) {
            while (System.nanoTime() < intended) {
                idle.idle(sub.poll(onEcho, 16));
            }
            out.putLong(0, intended);
            if (pub.offer(out, 0, 32) < 0) {
                lost[0]++;
            }
        }
        final long drain = System.nanoTime() + 1_000_000_000L;
        while (System.nanoTime() < drain) {
            idle.idle(sub.poll(onEcho, 16));
        }
        System.out.printf("AERON-RTT self=%s target=%s idle=%s count=%d min=%.1fus p50=%.1fus p90=%.1fus p99=%.1fus "
                + "p99.9=%.1fus max=%.1fus notOffered=%d%n", self, target, env("IDLE", "busyspin"), hist.getTotalCount(),
            hist.getMinValue() / 1e3, hist.getValueAtPercentile(50) / 1e3, hist.getValueAtPercentile(90) / 1e3,
            hist.getValueAtPercentile(99) / 1e3, hist.getValueAtPercentile(99.9) / 1e3, hist.getMaxValue() / 1e3, lost[0]);
    }

    private static String env(final String name, final String fallback) {
        final String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
