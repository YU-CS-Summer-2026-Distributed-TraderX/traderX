package finos.traderx.ordermatcher.cluster;

import com.sun.net.httpserver.HttpServer;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusterControl;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process proof of ArchivePurgeMain on a real 3-member Aeron 1.51 cluster (ClusterNodeConfig wiring, tiny
 * 64k log terms and 256k archive segments so a few MB of log spans many segments). Checks, in order:
 *   1. purge advances each member's log recording start to a segment boundary at or before S0, and deletes files;
 *   2. a lagging peer HOLDS the purge (start does not move), and releasing it lets the next poll purge;
 *   3. older snapshot recordings are gone, S0 and S1 remain;
 *   4. after purge, a full-cluster restart from disk recovers every member's state (snapshot + tail) and keeps going;
 *   5. negative control: a replay from position 0 is refused by the archive;
 *   6. the constraint it buys: an EMPTY-disk member cannot rejoin once the log is purged (its election replays the
 *      leader's log from position 0, which the archive refuses), so such a member is recovered from a peer's disk
 *      copy, never from empty. If this check ever fails, Aeron learned to seed a joiner from a snapshot: revisit.
 */
class ArchivePurgeTest {
    static final int PORT_BASE = 31000;
    static final List<String> HOSTS = List.of("localhost", "localhost", "localhost");

    /** Sums the payload longs; snapshot = {count, sum}. Same shape of determinism as the real engine. */
    static final class CountingService implements ClusteredService {
        final AtomicLong applied = new AtomicLong(-1), sum = new AtomicLong(), snapshots = new AtomicLong();
        volatile Cluster.Role role = Cluster.Role.FOLLOWER;
        Cluster cluster;

        public void onStart(final Cluster cluster, final Image snapshotImage) {
            this.cluster = cluster;
            applied.set(0);
            if (snapshotImage != null) {
                while (!snapshotImage.isEndOfStream() && applied.get() == 0 && sum.get() == 0) {
                    if (snapshotImage.poll((b, o, l, h) -> { applied.set(b.getLong(o)); sum.set(b.getLong(o + 8)); }, 1) == 0) {
                        cluster.idleStrategy().idle();
                    }
                }
            }
        }
        public void onSessionOpen(final ClientSession s, final long t) { }
        public void onSessionClose(final ClientSession s, final long t, final CloseReason r) { }
        public void onSessionMessage(final ClientSession s, final long t, final DirectBuffer b, final int o, final int l, final Header h) {
            sum.addAndGet(b.getLong(o));
            applied.incrementAndGet();
        }
        public void onTimerEvent(final long id, final long t) { }
        public void onTakeSnapshot(final ExclusivePublication p) {
            final UnsafeBuffer b = new UnsafeBuffer(new byte[16]);
            b.putLong(0, applied.get()); b.putLong(8, sum.get());
            while (p.offer(b, 0, 16) < 0) cluster.idleStrategy().idle();
            snapshots.incrementAndGet();
        }
        public void onRoleChange(final Cluster.Role r) { role = r; }
        public void onTerminate(final Cluster c) { }
    }

    static final class Member {
        final int id; final File base; final String aeronDir; CountingService svc; ClusteredMediaDriver md;
        ClusteredServiceContainer container; HttpServer health; volatile boolean stale;
        Member(final int id, final File root) { this.id = id; this.base = new File(root, "m" + id); this.aeronDir = new File(root, "aeron" + id).getPath(); }

        void start(final boolean clean) throws Exception {
            svc = new CountingService();
            final ClusterNodeConfig.Contexts c = ClusterNodeConfig.contexts(id, HOSTS, PORT_BASE, aeronDir, base, svc, clean);
            c.mediaDriver().threadingMode(ThreadingMode.SHARED);
            c.archive().segmentFileLength(256 * 1024);
            c.consensusModule().logChannel("aeron:udp?term-length=64k").leaderHeartbeatTimeoutNs(2_000_000_000L).startupCanvassTimeoutNs(4_000_000_000L).sessionTimeoutNs(300_000_000_000L);
            md = ClusteredMediaDriver.launch(c.mediaDriver(), c.archive(), c.consensusModule());
            container = ClusteredServiceContainer.launch(c.container());
            if (health == null) {
                health = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                health.createContext("/health", ex -> {
                    final byte[] body = ("{\"applied\":" + (stale ? 0 : svc.applied.get()) + "}").getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                    ex.close();
                });
                health.start();
            }
        }
        String url() { return "http://127.0.0.1:" + health.getAddress().getPort() + "/health"; }
        void stop() { CloseHelper.closeAll(container, md); }
        File archiveDir() { return new File(base, "archive"); }
        long segmentFiles() { final String[] f = archiveDir().list((d, n) -> n.endsWith(".rec")); return f == null ? 0 : f.length; }
    }

    static int failures;
    static void check(final boolean ok, final String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    @Test
    void purgesBehindTheSecondLatestSnapshotHoldsForALaggingPeerAndRecoversFromDisk() throws Exception {
        failures = 0;
        final File root = Files.createTempDirectory("hxpurge").toFile();
        final List<Member> members = new ArrayList<>();
        for (int i = 0; i < 3; i++) members.add(new Member(i, root));
        for (final Member m : members) m.start(true);
        System.out.println("members launched");
        final MediaDriver clientDriver = MediaDriver.launchEmbedded(new MediaDriver.Context().threadingMode(ThreadingMode.SHARED).dirDeleteOnShutdown(true));
        AeronCluster client = connect(clientDriver);
        System.out.println("client connected");
        final long[] expected = {0, 0};

        send(client, 4000, expected, members);   // ~2 MB of log per member: 8+ segments of 256k
        snapshot(members); send(client, 4000, expected, members);
        snapshot(members); send(client, 4000, expected, members);
        snapshot(members); send(client, 2000, expected, members);

        final List<String> urls = members.stream().map(Member::url).toList();
        final List<ArchivePurgeMain> purgers = new ArrayList<>();
        for (final Member m : members) purgers.add(new ArchivePurgeMain(new File(m.base, "cluster"), m.aeronDir, urls, 5000, 0, 70));

        System.out.println("1. purge behind S0");
        final long[] startBefore = new long[3];
        for (int i = 0; i < 3; i++) {
            final ArchivePurgeMain p = purgers.get(i);
            final ArchivePurgeMain.Snapshots s = p.snapshots();
            final long logId = s.logRecordingIds().iterator().next();
            startBefore[i] = p.descriptor(logId)[0];
            final long files = members.get(i).segmentFiles();
            final String line = p.poll();
            final long[] d = p.descriptor(logId);
            check(line != null && line.startsWith("PURGE "), "member " + i + " purged: " + line);
            check(d[0] > startBefore[i] && d[0] <= s.s0() && d[0] % d[3] == 0, "member " + i + " start " + startBefore[i] + "->" + d[0] + " aligned, <= S0 " + s.s0());
            final Member m = members.get(i);
            try { await(() -> m.segmentFiles() < files, "segment files deleted"); } catch (final IllegalStateException e) { /* reported below */ }
            check(m.segmentFiles() < files, "member " + i + " segment files " + files + "->" + m.segmentFiles());
            check(s.olderRecordingIds().stream().allMatch(id -> p.descriptor(id) == null), "member " + i + " older snapshot recordings purged " + s.olderRecordingIds());
            check(p.descriptor(s.logRecordingIds().iterator().next()) != null, "member " + i + " log recording still listed");
        }

        System.out.println("2. lagging peer holds the purge");
        members.get(2).stale = true;
        send(client, 4000, expected, members); snapshot(members); send(client, 1000, expected, members);
        final ArchivePurgeMain p0 = purgers.get(0);
        final long logId0 = p0.snapshots().logRecordingIds().iterator().next();
        final long held = p0.descriptor(logId0)[0];
        final String heldLine = p0.poll();
        check(heldLine != null && heldLine.startsWith("PURGE_HELD") && p0.descriptor(logId0)[0] == held, "held: " + heldLine);
        members.get(2).stale = false;
        final String released = p0.poll();
        check(released != null && released.startsWith("PURGE ") && p0.descriptor(logId0)[0] > held, "released: " + released);
        for (int i = 1; i < 3; i++) purgers.get(i).poll();

        System.out.println("5. negative control: replay from 0 refused");
        boolean refused;
        try {
            p0.archive().startReplay(logId0, 0, AeronArchive.NULL_LENGTH, "aeron:ipc", 1999);
            refused = false;
        } catch (final Exception e) {
            refused = true;
            System.out.println("     archive said: " + e.getMessage().split("\n")[0]);
        }
        check(refused, "startReplay(position 0) refused after purge");

        System.out.println("4. full restart from disk after purge: snapshot + tail");
        for (final ArchivePurgeMain p : purgers) p.close();
        client.close();
        for (final Member m : members) m.stop();
        for (final Member m : members) m.start(false);
        client = connect(clientDriver);
        for (final Member m : members) await(() -> m.svc.applied.get() == expected[0], "member " + m.id + " recovered applied=" + expected[0]);
        for (final Member m : members) check(m.svc.applied.get() == expected[0] && m.svc.sum.get() == expected[1], "member " + m.id + " recovered applied=" + m.svc.applied.get() + " sum=" + m.svc.sum.get());
        send(client, 1000, expected, members);
        for (final Member m : members) check(m.svc.sum.get() == expected[1], "member " + m.id + " applies after restart sum=" + m.svc.sum.get());

        System.out.println("6. an empty-disk member cannot rejoin after the purge");
        final Member j = members.get(2);
        j.stop();
        org.agrona.IoUtil.delete(j.base, true);
        j.start(true);
        boolean rejoined;
        try {
            await(() -> j.svc.applied.get() == expected[0], "empty-disk member applied " + expected[0]);
            rejoined = true;
        } catch (final IllegalStateException e) {
            rejoined = false;
        }
        check(!rejoined && j.svc.applied.get() == 0, "empty-disk member did not rejoin (applied " + j.svc.applied.get()
            + "/" + expected[0] + "): it needs the log from 0");

        client.close();
        for (final Member m : members) { m.stop(); m.health.stop(0); }
        clientDriver.close();
        org.agrona.IoUtil.delete(root, true);
        org.junit.jupiter.api.Assertions.assertEquals(0, failures, "see the FAIL lines above");
    }

    static AeronCluster connect(final MediaDriver driver) {
        return AeronCluster.connect(new AeronCluster.Context()
            .aeronDirectoryName(driver.aeronDirectoryName())
            .ingressChannel("aeron:udp")
            .ingressEndpoints(ClusterNodeConfig.ingressEndpoints(HOSTS, PORT_BASE))
            .egressChannel("aeron:udp?endpoint=localhost:0")
            .messageTimeoutNs(20_000_000_000L));
    }

    /** n messages of 400 bytes; first long is the value; waits until every member applied them. */
    static void send(final AeronCluster c, final int n, final long[] expected, final List<Member> members) throws Exception {
        final ExpandableArrayBuffer b = new ExpandableArrayBuffer(400);
        for (int i = 0; i < n; i++) {
            final long v = expected[0] + 1;
            b.putLong(0, v);
            final long deadline = System.currentTimeMillis() + 10_000;
            long r;
            while ((r = c.offer(b, 0, 400)) < 0) {
                c.pollEgress();
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("offer " + r + " at msg " + expected[0] + " roles/applied "
                        + members.stream().map(m -> m.svc.role + "/" + m.svc.applied.get()).toList());
                }
            }
            expected[0]++; expected[1] += v;
            c.pollEgress();
        }
        for (final Member m : members) await(() -> m.svc.applied.get() == expected[0], "member " + m.id + " applied " + expected[0]);
    }

    static void snapshot(final List<Member> members) throws Exception {
        final long[] before = members.stream().mapToLong(m -> m.svc.snapshots.get()).toArray();
        final Member leader = members.stream().filter(m -> m.svc.role == Cluster.Role.LEADER).findFirst().orElseThrow();
        final AtomicCounter toggle = ClusterControl.findControlToggle(leader.md.mediaDriver().context().countersManager(), 0);
        if (!ClusterControl.ToggleState.SNAPSHOT.toggle(toggle)) throw new IllegalStateException("toggle busy");
        for (int i = 0; i < 3; i++) { final int k = i; await(() -> members.get(k).svc.snapshots.get() > before[k], "member " + k + " snapshot"); }
        await(() -> ClusterControl.ToggleState.get(toggle) == ClusterControl.ToggleState.NEUTRAL, "toggle back to NEUTRAL");
        Thread.sleep(200);   // RecordingLog append on followers trails the service ack
    }

    interface Cond { boolean ok(); }
    static void await(final Cond c, final String what) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 30_000;
        while (!c.ok()) {
            if (System.currentTimeMillis() > deadline) throw new IllegalStateException("timeout: " + what);
            Thread.sleep(5);
        }
    }
}
