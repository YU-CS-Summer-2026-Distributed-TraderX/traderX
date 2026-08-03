package finos.traderx.ordermatcher.cluster;

import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.charset.StandardCharsets;

/**
 * Diagnostic (not shipped in the runtime path): bare Aeron unicast pub/sub to isolate whether
 * cross-pod Aeron UDP works AT ALL on this cluster, independent of Aeron Cluster egress. Raw
 * socat UDP is known to work here, so if this bare Aeron pub connects and the sub receives
 * cross-pod, the block is specific to the AeronCluster egress-channel setup (fixable in config);
 * if it does NOT, the block is Aeron-in-container UDP receive (environmental).
 *
 * MODE=sub: bind a Subscription to endpoint=POD_IP:PORT and print received payloads.
 * MODE=pub: create a Publication to endpoint=TARGET:PORT and offer until connected.
 */
public final class AeronPingPong {
    public static void main(final String[] args) throws Exception {
        final String mode = env("MODE", "sub");
        final int port = Integer.parseInt(env("PORT", "40470"));
        final String aeronDir = env("PP_AERON_DIR", "/dev/shm/aeron-pp");
        final MediaDriver driver = MediaDriver.launch(new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true));
        final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));

        if ("sub".equals(mode)) {
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

    private static String env(final String name, final String fallback) {
        final String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
