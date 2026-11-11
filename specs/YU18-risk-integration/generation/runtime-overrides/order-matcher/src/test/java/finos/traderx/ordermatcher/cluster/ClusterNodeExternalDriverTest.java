package finos.traderx.ordermatcher.cluster;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-GMR-14: CLUSTER_MEDIA_DRIVER=external launches no driver and attaches the archive, consensus module and service to
 * one already running in CLUSTER_AERON_DIR. A one-member node in a child JVM, its driver in ANOTHER process: this test
 * JVM's Java MediaDriver, or the C driver when {@code AERONMD=/path/to/aeronmd} is set (the L6a local proof). It must
 * elect, take an order from the proof client, and close on SIGTERM leaving the driver up; with no driver it must refuse.
 */
class ClusterNodeExternalDriverTest {
    @Test
    void attachesToAnExternalDriverElectsAndTakesAnOrder(@TempDir final Path dir) throws Exception {
        final String aeronDir = dir.resolve("aeron").toString();
        final String aeronmd = System.getenv("AERONMD");
        Process cDriver = null;
        MediaDriver javaDriver = null;
        if (aeronmd != null && !aeronmd.isBlank()) {
            final ProcessBuilder pb = new ProcessBuilder(aeronmd).redirectErrorStream(true)
                .redirectOutput(dir.resolve("aeronmd.log").toFile());
            pb.environment().putAll(Map.of("AERON_DIR", aeronDir, "AERON_DIR_DELETE_ON_START", "true",
                "AERON_TERM_BUFFER_SPARSE_FILE", "true", "AERON_THREADING_MODE", "DEDICATED"));
            cDriver = pb.start();
        } else {
            javaDriver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(aeronDir)
                .threadingMode(ThreadingMode.SHARED).termBufferSparseFile(true).dirDeleteOnStart(true));
        }
        System.out.println("external driver: " + (cDriver != null ? "C aeronmd " + aeronmd : "Java MediaDriver in the test JVM"));
        final int portBase = TestPorts.freeBase(1);
        final Child node = Child.start(ClusterNodeMain.class, Map.of("CLUSTER_MEDIA_DRIVER", "external",
            "CLUSTER_MEMBER_ID", "0", "CLUSTER_HOSTNAMES", "localhost", "CLUSTER_PORT_BASE", String.valueOf(portBase),
            "CLUSTER_BASE_DIR", dir.resolve("data").toString(), "CLUSTER_AERON_DIR", aeronDir,
            "HEALTH_PORT", "0", "ALLOW_DEV_SECRETS", "true"));
        Child client = null;
        try {
            node.await("ELECTION-PHASE state=CLOSED", 90);
            assertTrue(node.out().contains("mediaDriver=external"), node::out);
            client = Child.start(ClusterProofClient.class, Map.of(
                "PROOF_INGRESS_ENDPOINTS", "0=localhost:" + ClusterNodeConfig.port(0, portBase,
                    ClusterNodeConfig.CLIENT_FACING_PORT_OFFSET),
                "PROOF_AERON_DIR", dir.resolve("client-aeron").toString(), "ALLOW_DEV_SECRETS", "true"));
            client.await("ACK ref=", 60);
            client.process.destroyForcibly();

            node.process.toHandle().destroy();   // SIGTERM
            assertTrue(node.process.waitFor(30, TimeUnit.SECONDS), node::out);
            assertEquals(143, node.process.exitValue(), node::out);
            node.reader.join(5_000);
            assertTrue(node.out().contains("Cluster node down") && node.out().contains("external media driver left running"),
                node::out);
            assertTrue(cDriver == null || cDriver.isAlive(), "the C driver died with the member");
            System.out.println(node.out());
        } finally {
            if (client != null) client.process.destroyForcibly();
            node.process.destroyForcibly();
            if (cDriver != null) cDriver.destroyForcibly();
            CloseHelper.quietClose(javaDriver);
        }
    }

    @Test
    void refusesWithoutADriver(@TempDir final Path dir) throws Exception {
        final Child node = Child.start(ClusterNodeMain.class, Map.of("CLUSTER_MEDIA_DRIVER", "external",
            "CLUSTER_EXTERNAL_DRIVER_WAIT_MS", "1000", "CLUSTER_MEMBER_ID", "0", "CLUSTER_HOSTNAMES", "localhost",
            "CLUSTER_PORT_BASE", String.valueOf(TestPorts.freeBase(1)), "CLUSTER_BASE_DIR", dir.resolve("data").toString(),
            "CLUSTER_AERON_DIR", dir.resolve("aeron").toString(), "HEALTH_PORT", "0", "ALLOW_DEV_SECRETS", "true"));
        try {
            assertTrue(node.process.waitFor(30, TimeUnit.SECONDS), node::out);
            node.reader.join(5_000);
            assertEquals(1, node.process.exitValue(), node::out);
            assertTrue(node.out().contains("no live media driver"), node::out);
        } finally {
            node.process.destroyForcibly();
        }
    }

    private record Child(Process process, StringBuilder buf, Thread reader) {
        static Child start(final Class<?> main, final Map<String, String> env) throws Exception {
            final ProcessBuilder pb = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "-Xmx256m",
                "-cp", System.getProperty("java.class.path"), main.getName()).redirectErrorStream(true);
            pb.environment().putAll(env);
            final Process p = pb.start();
            final StringBuilder buf = new StringBuilder();
            final Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null; ) synchronized (buf) { buf.append(line).append('\n'); }
                } catch (final java.io.IOException ignored) { }
            });
            reader.start();
            return new Child(p, buf, reader);
        }

        String out() {
            synchronized (buf) { return buf.toString(); }
        }

        void await(final String text, final int seconds) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (!out().contains(text) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(out().contains(text), () -> "never printed '" + text + "':\n" + out());
        }
    }
}
