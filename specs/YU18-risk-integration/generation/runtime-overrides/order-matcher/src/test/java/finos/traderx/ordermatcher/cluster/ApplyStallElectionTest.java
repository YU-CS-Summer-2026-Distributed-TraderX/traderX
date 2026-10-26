package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.PositionBook;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EOD-RISK item 3, the discriminating experiment: does a long stall on the deterministic apply thread
 * — the size of a 1M-position cut render — start an election at the rig's consensus timings
 * (heartbeat interval 50 ms, timeout 200 ms, election 100 ms)? Every member stalls, as every member
 * renders. Arms: a pure 600 ms sleep, and the real render of a synthetic 1M-position cut (CPU and
 * ~150 MB of allocation). Verdict = leadership terms counted by every member's service: unchanged
 * means no election. The positive control kills the leader and must see the count rise, so a
 * probe that cannot see elections cannot pass the two arms.
 *
 * <p>Why it holds: the consensus module is its own agent; leader heartbeats and commit do not wait
 * on the service. A stall that DOES elect is one that stops the consensus module too — a JVM-wide
 * pause (GC/safepoint) or CPU starvation — which no choice of render thread removes.
 */
@Timeout(180)
class ApplyStallElectionTest {
    private static final int PORT_BASE = 22800;
    private static final List<String> HOSTNAMES = List.of("localhost", "localhost", "localhost");

    @TempDir
    Path tempDir;

    private final ClusteredMediaDriver[] drivers = new ClusteredMediaDriver[3];
    private final ClusteredServiceContainer[] containers = new ClusteredServiceContainer[3];
    private final Stalling[] services = new Stalling[3];
    private io.aeron.driver.MediaDriver clientDriver;
    private AeronCluster client;

    @AfterEach
    void tearDown() {
        CloseHelper.quietCloseAll(client, clientDriver);
        for (int i = 0; i < 3; i++) {
            CloseHelper.quietCloseAll(containers[i], drivers[i]);
        }
    }

    @Test
    void sixHundredMsSleepOnEveryApplyThreadDoesNotElect() throws Exception {
        stallOnMarker(() -> sleep(600), 600);
    }

    @Test
    void millionPositionRenderOnEveryApplyThreadDoesNotElect() throws Exception {
        final PositionBook book = new PositionBook(1 << 14);
        final java.util.SplittableRandom r = new java.util.SplittableRandom(42);
        while (book.size() < 1_000_000) {
            book.bookTrade(r.nextInt(50_000) + 1, r.nextInt(5000), r.nextInt(1, 1000),
                1_000_000L + r.nextInt(100_000_000));
        }
        final String[] tickers = new String[5000];
        final List<long[]> prices = new ArrayList<>();
        for (int i = 0; i < tickers.length; i++) {
            tickers[i] = "SYM" + i;
            prices.add(new long[] {i, 1_000_000L + i});
        }
        stallOnMarker(() -> RiskExtractCut.sha256(RiskExtractCut.render(1, 20000, 1, book.tuples(), prices,
            tickers, s -> 1L, List.of())), 200);
    }

    @Test
    void probeSeesAnElectionWhenTheLeaderDies() throws Exception {
        launchAll(() -> { });
        int leader = -1;
        for (int i = 0; i < 3; i++) {
            if (services[i].role == Cluster.Role.LEADER) {
                leader = i;
            }
        }
        assertTrue(leader >= 0, "a leader was elected");
        CloseHelper.quietCloseAll(containers[leader], drivers[leader]);
        containers[leader] = null;
        drivers[leader] = null;
        services[leader].terms.set(0);
        final long before = terms();
        final long deadline = System.currentTimeMillis() + 30_000;
        while (terms() == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        System.out.println("APPLY-STALL control termsBefore=" + before + " termsAfter=" + terms());
        assertTrue(terms() > before, "the probe counted the new leadership term");
    }

    private void stallOnMarker(final Runnable stall, final long minStallMs) throws Exception {
        launchAll(stall);
        final long termsBefore = terms();
        final UnsafeBuffer marker = new UnsafeBuffer(new byte[AeronReplicationCodec.RISK_EXTRACT_BYTES]);
        new AeronReplicationCodec().encodeRiskExtract(marker, 0, 1, 20000, 1);
        while (client.offer(marker, 0, marker.capacity()) < 0) {
            client.pollEgress();
            Thread.onSpinWait();
        }
        final long deadline = System.currentTimeMillis() + 60_000;
        while (stalls() < 3 && System.currentTimeMillis() < deadline) {
            client.pollEgress();
            Thread.sleep(1);
        }
        assertEquals(3, stalls(), "every member stalled");
        final long settle = System.currentTimeMillis() + 3_000;   // 15x the heartbeat timeout
        while (System.currentTimeMillis() < settle) {
            client.pollEgress();
            Thread.sleep(5);
        }
        long maxStall = 0;
        for (final Stalling s : services) {
            maxStall = Math.max(maxStall, s.stallMs.get());
        }
        System.out.println("APPLY-STALL maxStallMs=" + maxStall + " termsBefore=" + termsBefore
            + " termsAfter=" + terms());
        assertTrue(maxStall >= minStallMs, "the stall exceeded the 200 ms heartbeat timeout: " + maxStall);
        assertEquals(termsBefore, terms(), "a stall on the apply thread started an election");
    }

    private void launchAll(final Runnable stall) throws Exception {
        for (int i = 0; i < 3; i++) {
            services[i] = new Stalling(stall);
            final ClusterNodeConfig.Contexts c = ClusterNodeConfig.contexts(i, HOSTNAMES, PORT_BASE,
                new File(tempDir.toFile(), "aeron-" + i).getAbsolutePath(),
                new File(tempDir.toFile(), "node-" + i), services[i], true);
            c.consensusModule().leaderHeartbeatIntervalNs(50_000_000L).leaderHeartbeatTimeoutNs(200_000_000L)
                .electionTimeoutNs(100_000_000L);
            drivers[i] = ClusteredMediaDriver.launch(c.mediaDriver(), c.archive(), c.consensusModule());
            containers[i] = ClusteredServiceContainer.launch(c.container());
        }
        clientDriver = io.aeron.driver.MediaDriver.launch(new io.aeron.driver.MediaDriver.Context()
            .aeronDirectoryName(new File(tempDir.toFile(), "client-aeron").getAbsolutePath())
            .threadingMode(io.aeron.driver.ThreadingMode.SHARED).termBufferSparseFile(true)
            .dirDeleteOnStart(true));
        client = AeronCluster.connect(new AeronCluster.Context()
            .aeronDirectoryName(clientDriver.aeronDirectoryName())
            .ingressChannel("aeron:udp?term-length=64k")
            .ingressEndpoints(ClusterNodeConfig.ingressEndpoints(HOSTNAMES, PORT_BASE))
            .egressChannel("aeron:udp?endpoint=localhost:0")
            .egressListener((id, ts, b, o, l, h) -> { }));
        final long deadline = System.currentTimeMillis() + 10_000;   // every member saw the first term
        while (terms() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    private long terms() {
        long n = 0;
        for (final Stalling s : services) {
            n += s.terms.get();
        }
        return n;
    }

    private int stalls() {
        int n = 0;
        for (final Stalling s : services) {
            n += s.stalled.get();
        }
        return n;
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A bare service: counts leadership terms and stalls once per session message. */
    private static final class Stalling implements ClusteredService {
        final Runnable stall;
        final AtomicInteger stalled = new AtomicInteger();
        final AtomicLong terms = new AtomicLong();
        final AtomicLong stallMs = new AtomicLong();
        volatile Cluster.Role role = Cluster.Role.FOLLOWER;

        Stalling(final Runnable stall) {
            this.stall = stall;
        }

        @Override public void onStart(final Cluster cluster, final Image snapshotImage) { }
        @Override public void onSessionOpen(final ClientSession session, final long timestamp) { }
        @Override public void onSessionClose(final ClientSession session, final long timestamp, final CloseReason r) { }
        @Override public void onSessionMessage(final ClientSession session, final long timestamp,
                                               final DirectBuffer buffer, final int offset, final int length,
                                               final Header header) {
            final long t0 = System.nanoTime();
            stall.run();
            stallMs.set((System.nanoTime() - t0) / 1_000_000);
            stalled.incrementAndGet();
        }
        @Override public void onTimerEvent(final long correlationId, final long timestamp) { }
        @Override public void onTakeSnapshot(final ExclusivePublication snapshotPublication) { }
        @Override public void onRoleChange(final Cluster.Role newRole) {
            role = newRole;
        }
        @Override public void onTerminate(final Cluster cluster) { }
        @Override public void onNewLeadershipTermEvent(final long leadershipTermId, final long logPosition,
                                                       final long timestamp, final long termBaseLogPosition,
                                                       final int leaderMemberId, final int logSessionId,
                                                       final java.util.concurrent.TimeUnit timeUnit,
                                                       final int appVersion) {
            terms.incrementAndGet();
        }
    }
}
