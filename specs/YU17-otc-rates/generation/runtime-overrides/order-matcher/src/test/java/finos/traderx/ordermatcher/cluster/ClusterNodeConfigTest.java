package finos.traderx.ordermatcher.cluster;

import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterNodeConfigTest {

    @Test
    void memberThreadingDefaultsToDedicatedAndParsesTheOthers() {
        assertEquals(ThreadingMode.DEDICATED, ClusterNodeConfig.memberThreading(null));
        assertEquals(ThreadingMode.DEDICATED, ClusterNodeConfig.memberThreading(" "));
        assertEquals(ThreadingMode.SHARED, ClusterNodeConfig.memberThreading("shared"));
        assertEquals(ThreadingMode.SHARED_NETWORK, ClusterNodeConfig.memberThreading(" SHARED_NETWORK "));
        assertThrows(IllegalArgumentException.class, () -> ClusterNodeConfig.memberThreading("dedicate"));
    }

    @Test
    void maxSessionsDefaultsTo64() {
        assertEquals(64, ClusterNodeConfig.maxSessions(null));
        assertEquals(64, ClusterNodeConfig.maxSessions(""));
        assertEquals(128, ClusterNodeConfig.maxSessions("128"));
    }

    @Test
    void contextsCarryTheDefaultsWhenNothingIsSet() {
        // Guards the wiring, not just the parsers: an unset environment must reach the contexts.
        assertNull(System.getenv("CLUSTER_MEDIA_DRIVER_THREADING"));
        assertNull(System.getenv("CLUSTER_MAX_SESSIONS"));
        assertNull(System.getProperty("aeron.cluster.max.sessions"));
        final ClusterNodeConfig.Contexts c = ClusterNodeConfig.contexts(0, List.of("localhost", "localhost", "localhost"),
            9000, "/tmp/unused-aeron", new File("/tmp/unused-cluster"), null, false);
        assertEquals(ThreadingMode.DEDICATED, c.mediaDriver().threadingMode());
        assertEquals(64, c.consensusModule().maxConcurrentSessions());
        // FR-GMR-12/13: with no L1/L2 knob set the member is today's member (Aeron's default idle, SHARED archive).
        assertNull(System.getenv("CLUSTER_IDLE_STRATEGY"));
        assertNull(System.getenv("CLUSTER_IDLE_SLEEP_MS"));
        ClusterNodeConfig.IDLE_AGENTS.forEach(a -> assertNull(System.getenv("CLUSTER_IDLE_STRATEGY_" + a)));
        assertNull(System.getenv("CLUSTER_ARCHIVE_THREADING"));
        assertNull(c.mediaDriver().senderIdleStrategy());
        assertNull(c.mediaDriver().receiverIdleStrategy());
        assertEquals(ArchiveThreadingMode.SHARED, c.archive().threadingMode());
        assertEquals("sender=aeron-default receiver=aeron-default conductor=aeron-default archive=aeron-default "
            + "consensus=aeron-default service=aeron-default", ClusterNodeConfig.describeIdle());
    }

    @Test
    void archiveThreadingDefaultsToSharedAndParsesDedicated() {
        assertEquals(ArchiveThreadingMode.SHARED, ClusterNodeConfig.archiveThreading(null));
        assertEquals(ArchiveThreadingMode.SHARED, ClusterNodeConfig.archiveThreading(" "));
        assertEquals(ArchiveThreadingMode.DEDICATED, ClusterNodeConfig.archiveThreading(" dedicated "));
        assertThrows(IllegalArgumentException.class, () -> ClusterNodeConfig.archiveThreading("dedicate"));
    }

    @Test
    void idleStrategyNamesParseAndAnUnknownOneNamesItsKnob() {
        assertNull(ClusterNodeConfig.idleStrategy("K", null));
        assertNull(ClusterNodeConfig.idleStrategy("K", " "));
        assertInstanceOf(NoOpIdleStrategy.class, ClusterNodeConfig.idleStrategy("K", " BusySpin ").get());
        assertInstanceOf(BackoffIdleStrategy.class, ClusterNodeConfig.idleStrategy("K", "lowpark").get());
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ClusterNodeConfig.idleStrategy("CLUSTER_IDLE_STRATEGY_SENDER", "spin"));
        assertTrue(e.getMessage().contains("CLUSTER_IDLE_STRATEGY_SENDER"), e.getMessage());
    }

    @Test
    void anAgentsOwnIdleWinsOverTheMemberWideOne() {
        final Supplier<IdleStrategy> own = NoOpIdleStrategy::new;
        final Supplier<IdleStrategy> member = () -> new BackoffIdleStrategy(10, 5, 1000, 1_000L);
        assertSame(own, ClusterNodeConfig.agentIdle(own, member));
        assertSame(member, ClusterNodeConfig.agentIdle(null, member));
        assertNull(ClusterNodeConfig.agentIdle(null, null));
    }

    @Test
    void theKnobsReachTheContextsInAMemberJvm() throws Exception {
        // The tests above cannot set the environment: this one forks a JVM that has it, so the env -> context wiring is
        // exercised, not only the parsers. The archive conductor and consensus module keep the member-wide lowpark.
        final ProcessBuilder pb = new ProcessBuilder(
            new File(System.getProperty("java.home"), "bin/java").getPath(),
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "-cp", System.getProperty("java.class.path"), KnobProbe.class.getName()).redirectErrorStream(true);
        final Map<String, String> env = pb.environment();
        IDLE_KEYS.forEach(env::remove);
        env.put("CLUSTER_IDLE_STRATEGY", "lowpark");
        env.put("CLUSTER_IDLE_STRATEGY_SENDER", "busyspin");
        env.put("CLUSTER_IDLE_STRATEGY_SERVICE", "busyspin");
        env.put("CLUSTER_ARCHIVE_THREADING", "dedicated");
        final Process p = pb.start();
        final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals("sender=NoOpIdleStrategy receiver=BackoffIdleStrategy conductor=BackoffIdleStrategy "
            + "archive=BackoffIdleStrategy consensus=BackoffIdleStrategy service=NoOpIdleStrategy archiveThreading=DEDICATED "
            + "idle=[sender=busyspin receiver=lowpark conductor=lowpark archive=lowpark consensus=lowpark service=busyspin]", out);
    }

    private static final List<String> IDLE_KEYS = List.of("CLUSTER_IDLE_STRATEGY", "CLUSTER_IDLE_SLEEP_MS",
        "CLUSTER_IDLE_STRATEGY_SENDER", "CLUSTER_IDLE_STRATEGY_RECEIVER", "CLUSTER_IDLE_STRATEGY_CONDUCTOR",
        "CLUSTER_IDLE_STRATEGY_ARCHIVE", "CLUSTER_IDLE_STRATEGY_CONSENSUS", "CLUSTER_IDLE_STRATEGY_SERVICE",
        "CLUSTER_ARCHIVE_THREADING");

    /** Run in the forked JVM: the idle each context was given, and the archive's threading. */
    public static final class KnobProbe {
        public static void main(final String[] args) {
            final ClusterNodeConfig.Contexts c = ClusterNodeConfig.contexts(0, List.of("localhost", "localhost", "localhost"),
                9000, "/tmp/unused-aeron", new File("/tmp/unused-cluster"), null, false);
            System.out.println("sender=" + name(c.mediaDriver().senderIdleStrategy())
                + " receiver=" + name(c.mediaDriver().receiverIdleStrategy())
                + " conductor=" + name(c.mediaDriver().conductorIdleStrategy())
                + " archive=" + name(c.archive().idleStrategy())
                + " consensus=" + name(c.consensusModule().idleStrategy())
                + " service=" + name(c.container().idleStrategy())
                + " archiveThreading=" + c.archive().threadingMode()
                + " idle=[" + ClusterNodeConfig.describeIdle() + "]");
        }

        private static String name(final IdleStrategy s) {
            return s == null ? "null" : s.getClass().getSimpleName();
        }
    }
}
