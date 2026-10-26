package finos.traderx.ordermatcher.cluster;

import java.io.File;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A member port block no other test on this host holds, for the in-process cluster tests. Fixed bases
 * (21800, 22800, 31000, ...) made two suites running at the same time fail each other with "Address already
 * in use" (2026-10-26). {@link #freeBase(int)} picks a random 100-aligned base, probes every
 * {@link ClusterNodeConfig} offset of every member on TCP and UDP (SO_REUSEADDR off, so a live Aeron socket
 * on the port is seen), and keeps a file lock on {@code <tmpdir>/traderx-test-ports/<base>} for the JVM's life
 * so a second JVM probing between this probe and the cluster's bind cannot choose the same block.
 */
public final class TestPorts {
    static final int LOW = 20_000, HIGH = 60_000;
    private static final List<FileChannel> HELD = new ArrayList<>();   // locks live until the JVM exits

    private TestPorts() { }

    /** A base for {@code members} consecutive {@link ClusterNodeConfig#PORTS_PER_NODE} blocks. */
    public static synchronized int freeBase(final int members) {
        final File dir = new File(System.getProperty("java.io.tmpdir"), "traderx-test-ports");
        dir.mkdirs();
        final int span = members * ClusterNodeConfig.PORTS_PER_NODE;
        for (int attempt = 0; attempt < 200; attempt++) {
            final int base = LOW + ThreadLocalRandom.current().nextInt((HIGH - LOW - span) / 100) * 100;
            FileChannel ch = null;
            try {
                ch = FileChannel.open(new File(dir, base + ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                final FileLock lock = ch.tryLock();
                if (lock == null) { ch.close(); continue; }       // another JVM holds this block
                if (!free(base, members)) { lock.release(); ch.close(); continue; }
                HELD.add(ch);
                return base;
            } catch (final Exception e) {
                if (ch != null) { try { ch.close(); } catch (final Exception ignore) { } }
            }
        }
        throw new IllegalStateException("no free member port block in [" + LOW + "," + HIGH + ")");
    }

    private static boolean free(final int base, final int members) {
        for (int m = 0; m < members; m++) {
            for (int off = ClusterNodeConfig.ARCHIVE_CONTROL_PORT_OFFSET; off <= ClusterNodeConfig.CM_REPLICATION_PORT_OFFSET; off++) {
                final int port = ClusterNodeConfig.port(m, base, off);
                try (ServerSocket tcp = new ServerSocket(); DatagramSocket udp = new DatagramSocket(null)) {
                    tcp.setReuseAddress(false); udp.setReuseAddress(false);
                    tcp.bind(new InetSocketAddress(port)); udp.bind(new InetSocketAddress(port));
                } catch (final Exception inUse) {
                    return false;
                }
            }
        }
        return true;
    }
}
