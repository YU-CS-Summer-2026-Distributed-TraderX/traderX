package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SIGTERM must end a member. Under Agrona 2.x the ShutdownSignalBarrier's own shutdown hook waits for close(), and
 * ClusterNodeMain never called it, so a stopped pod logged "ShutdownSignalBarrier hasn't terminated" every 10 s until
 * the kubelet's SIGKILL: the archive and media driver were never closed. Runs a real one-member node in a child JVM.
 * The exit code is the point: on one member the old code still died, but by the consensus-terminated {@code halt(70)}
 * (not a clean close, and on a 3-member GKE pod that path never fired). 143 = 128 + SIGTERM, the JVM's normal exit.
 */
class ClusterNodeShutdownTest {
    @Test
    void sigtermEndsTheNode(@TempDir final Path dir) throws Exception {
        final ProcessBuilder pb = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "-Xmx256m",
            "-cp", System.getProperty("java.class.path"), ClusterNodeMain.class.getName())
            .redirectErrorStream(true);
        pb.environment().put("CLUSTER_MEMBER_ID", "0");
        pb.environment().put("CLUSTER_HOSTNAMES", "localhost");
        pb.environment().put("CLUSTER_PORT_BASE", "27800");
        pb.environment().put("CLUSTER_BASE_DIR", dir.resolve("data").toString());
        pb.environment().put("CLUSTER_AERON_DIR", dir.resolve("aeron").toString());
        pb.environment().put("HEALTH_PORT", "0");
        pb.environment().put("ALLOW_DEV_SECRETS", "true");
        final Process node = pb.start();
        final StringBuilder out = new StringBuilder();
        final Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(node.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) synchronized (out) { out.append(line).append('\n'); }
            } catch (final java.io.IOException ignored) { }
        });
        reader.start();
        try {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (!snapshot(out).contains("Cluster node up") && node.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(snapshot(out).contains("Cluster node up"), () -> "node never came up:\n" + snapshot(out));

            node.destroy();   // SIGTERM, as the kubelet sends
            assertTrue(node.waitFor(30, TimeUnit.SECONDS), () -> "node ignored SIGTERM:\n" + snapshot(out));
            reader.join(5_000);
            assertFalse(snapshot(out).contains("hasn't terminated"), () -> snapshot(out));
            assertEquals(143, node.exitValue(), () -> "not a clean SIGTERM exit:\n" + snapshot(out));
        } finally {
            node.destroyForcibly();
        }
    }

    private static String snapshot(final StringBuilder out) {
        synchronized (out) { return out.toString(); }
    }
}
