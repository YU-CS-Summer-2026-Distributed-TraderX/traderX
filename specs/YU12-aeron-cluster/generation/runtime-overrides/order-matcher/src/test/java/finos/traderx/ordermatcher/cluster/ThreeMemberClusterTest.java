package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import io.aeron.cluster.ClusterTool;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three-member Raft proof, in process (T-AC13/14 at in-process scope — the kind run repeats it
 * across pods): majority election; a cluster-wide snapshot; leader kill with client-observed
 * failover to the new leader; a member wiped to EMPTY disk rejoining and converging to
 * byte-equal deterministic state; a second leader kill with the recovered member in the voting
 * set; and the strict no-ID-reuse lineage across the whole run. The wiped-member rejoin is the
 * capability the parent state spent five slices hand-building — here it must be a primitive.
 */
@Timeout(300)
class ThreeMemberClusterTest {
    private static final long PX = 1_000_000L;
    private static final int SECURITY = 1;
    private static final int ACCOUNT = 11;
    private static final int PORT_BASE = 21800;
    private static final List<String> HOSTNAMES = List.of("localhost", "localhost", "localhost");

    @TempDir
    Path tempDir;

    private final Node[] nodes = new Node[3];
    private io.aeron.driver.MediaDriver clientDriver;
    private AeronCluster client;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final InputEvent ingress = new InputEvent();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final List<long[]> acks = new ArrayList<>(); // {orderRef, kind, tradeSeq}

    private static final class Node {
        final int id;
        ClusteredMediaDriver driver;
        ClusteredServiceContainer container;
        MatchingEngineClusteredService service;

        Node(final int id) {
            this.id = id;
        }
    }

    @AfterEach
    void tearDown() {
        CloseHelper.quietCloseAll(client, clientDriver);
        for (final Node node : nodes) {
            if (node != null) {
                CloseHelper.quietCloseAll(node.container, node.driver);
            }
        }
    }

    @Test
    void wipedMemberRejoinsAndLineageSurvivesTwoFailovers() {
        for (int i = 0; i < 3; i++) {
            launch(i, true);
        }
        final int leader1 = awaitLeader(-1);
        connectClient();

        // Sequenced control seeding + a book: three resting orders through the consensus log.
        offerAccountControl(ACCOUNT, true);
        offerSecurityControl(SECURITY, true);
        offerPriceTick(150 * PX);
        offerNewOrder(100 * PX);
        offerNewOrder(100 * PX);
        offerNewOrder(100 * PX);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 3);
        assertEquals(List.of(1L, 2L, 3L), ackRefs(OutputEvent.KIND_ORDER_ACCEPTED));

        // A snapshot is a log-ordered, cluster-wide action: every member takes it.
        takeSnapshot(leader1);
        acks.clear();
        offerNewOrder(100 * PX);
        offerNewOrder(100 * PX);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 2);
        assertEquals(List.of(4L, 5L), ackRefs(OutputEvent.KIND_ORDER_ACCEPTED));

        // Failover 1: kill the leader; the majority elects; the SAME client session carries the
        // next order to the new leader; the lineage continues at 6.
        stop(leader1);
        final int leader2 = awaitLeader(leader1);
        assertNotEquals(leader1, leader2);
        acks.clear();
        offerNewOrder(100 * PX);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 1);
        assertEquals(List.of(6L), ackRefs(OutputEvent.KIND_ORDER_ACCEPTED));

        // The falsifiable gate: wipe the dead member's disks COMPLETELY and relaunch the same
        // member id. The cluster must bring it back to identical deterministic state with no
        // hand-built bundle transport — this replaces the parent state's five recovery slices.
        wipeDirs(leader1);
        launch(leader1, false);
        awaitCatchUp(leader1);
        assertClusterStateEquality(leader1, leader2);

        // Failover 2: kill the current leader with the recovered member in the voting set.
        stop(leader2);
        final int leader3 = awaitLeader(leader2);
        assertNotEquals(leader2, leader3);
        acks.clear();
        offerNewOrder(100 * PX);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 1);
        assertEquals(List.of(7L), ackRefs(OutputEvent.KIND_ORDER_ACCEPTED),
            "no ID is ever reused across two failovers and a wiped-member recovery");

        // Fill everything through the current leader: trade sequencing is one unbroken lineage.
        acks.clear();
        offerPriceTick(90 * PX);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_FILLED) == 7);
        final long maxTradeSeq = acks.stream()
            .filter(a -> a[1] == OutputEvent.KIND_TRADE_BOOKED)
            .mapToLong(a -> a[2]).max().orElse(-1);
        assertEquals(7, maxTradeSeq);
    }

    // ----- harness ---------------------------------------------------------------------------

    private void launch(final int id, final boolean cleanStart) {
        final Node node = new Node(id);
        node.service = new MatchingEngineClusteredService();
        final ClusterNodeConfig.Contexts contexts = ClusterNodeConfig.contexts(id, HOSTNAMES, PORT_BASE,
            new File(tempDir.toFile(), "aeron-" + id).getAbsolutePath(),
            new File(tempDir.toFile(), "node-" + id), node.service, cleanStart);
        node.driver = ClusteredMediaDriver.launch(
            contexts.mediaDriver(), contexts.archive(), contexts.consensusModule());
        node.container = ClusteredServiceContainer.launch(contexts.container());
        nodes[id] = node;
    }

    private void stop(final int id) {
        CloseHelper.quietCloseAll(nodes[id].container, nodes[id].driver);
        nodes[id] = null;
    }

    private void wipeDirs(final int id) {
        IoUtil.delete(new File(tempDir.toFile(), "node-" + id), true);
        IoUtil.delete(new File(tempDir.toFile(), "aeron-" + id), true);
    }

    private int awaitLeader(final int excluded) {
        final int[] leader = { -1 };
        await(() -> {
            for (final Node node : nodes) {
                if (node != null && node.id != excluded && node.service.role() == Cluster.Role.LEADER) {
                    leader[0] = node.id;
                    return true;
                }
            }
            return false;
        });
        return leader[0];
    }

    private void takeSnapshot(final int leaderId) {
        final int[] before = new int[3];
        for (final Node node : nodes) {
            if (node != null) {
                before[node.id] = node.service.snapshotsTaken();
            }
        }
        assertTrue(ClusterTool.snapshot(
            nodes[leaderId].driver.consensusModule().context().clusterDir(),
            new PrintStream(OutputStream.nullOutputStream())), "snapshot trigger accepted");
        await(() -> {
            for (final Node node : nodes) {
                if (node != null && node.service.snapshotsTaken() <= before[node.id]) {
                    return false;
                }
            }
            return true;
        });
    }

    /** The recovered member has converged when it reaches the most advanced applied sequence. */
    private void awaitCatchUp(final int id) {
        await(() -> {
            long maxSeq = 0;
            for (final Node node : nodes) {
                if (node != null && node.service.engine() != null) {
                    maxSeq = Math.max(maxSeq, node.service.engine().blpSeq());
                }
            }
            final MatchingEngineClusteredService recovered = nodes[id].service;
            return recovered.engine() != null && recovered.engine().blpSeq() == maxSeq && maxSeq > 0;
        });
    }

    /** Deterministic-state equality between two quiesced members (invariant 3's real test:
     *  not just the book — generators, risk aggregates, and eviction order). */
    private void assertClusterStateEquality(final int a, final int b) {
        final MatchingEngineClusteredService sa = nodes[a].service;
        final MatchingEngineClusteredService sb = nodes[b].service;
        assertEquals(sb.engine().blpSeq(), sa.engine().blpSeq());
        assertEquals(sb.nextOrderRef(), sa.nextOrderRef(), "generator identical on every member");
        assertEquals(sb.engine().tradeCounter(), sa.engine().tradeCounter());
        assertEquals(sb.engine().openOrderTuples().size(), sa.engine().openOrderTuples().size());
        assertEquals(toList(sb.engine().terminalOrderRefsFifo()), toList(sa.engine().terminalOrderRefsFifo()),
            "terminal eviction order identical on every member");
        assertEquals(sb.risk().reservedNotional(ACCOUNT), sa.risk().reservedNotional(ACCOUNT));
        assertEquals(sb.risk().executedNotional(ACCOUNT), sa.risk().executedNotional(ACCOUNT));
        assertEquals(sb.risk().policyVersion(), sa.risk().policyVersion());
    }

    private static List<Integer> toList(final int[] values) {
        final List<Integer> out = new ArrayList<>(values.length);
        for (final int v : values) {
            out.add(v);
        }
        return out;
    }

    // ----- client ----------------------------------------------------------------------------

    private void connectClient() {
        acks.clear();
        // The client owns its driver so member kills never take the client down with them.
        clientDriver = io.aeron.driver.MediaDriver.launch(new io.aeron.driver.MediaDriver.Context()
            .aeronDirectoryName(new File(tempDir.toFile(), "client-aeron").getAbsolutePath())
            .threadingMode(io.aeron.driver.ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true));
        client = AeronCluster.connect(
            new AeronCluster.Context()
                .aeronDirectoryName(clientDriver.aeronDirectoryName())
                .ingressChannel("aeron:udp?term-length=64k")
                .ingressEndpoints(ClusterNodeConfig.ingressEndpoints(HOSTNAMES, PORT_BASE))
                .egressChannel("aeron:udp?endpoint=localhost:0")
                .egressListener((clusterSessionId, timestamp, buffer, offset, length, header) ->
                    onEgress(buffer, offset)));
    }

    private void onEgress(final DirectBuffer buffer, final int offset) {
        acks.add(new long[] {
            buffer.getInt(offset + 8), buffer.getByte(offset + 12), buffer.getLong(offset + 13) });
    }

    /** Offer with failover tolerance: keep polling egress; if the client's session dies during
     *  an election window, reconnect through the surviving ingress endpoints and resend. */
    private void offerIngress() {
        codec.encodeInput(ingressBuffer, 0, ingress, 0, 0, 0);
        final long deadline = System.currentTimeMillis() + 60_000;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("ingress not accepted within 60s");
            }
            try {
                if (!client.isClosed()) {
                    client.pollEgress();
                    if (client.offer(ingressBuffer, 0, AeronReplicationCodec.INPUT_BYTES) > 0) {
                        return;
                    }
                    Thread.yield();
                    continue;
                }
            } catch (final Exception ignore) {
                // fall through to reconnect
            }
            CloseHelper.quietClose(client);
            try {
                connectClientKeepingAcks();
            } catch (final Exception ignore) {
                sleepQuietly();
            }
        }
    }

    private void connectClientKeepingAcks() {
        client = AeronCluster.connect(
            new AeronCluster.Context()
                .aeronDirectoryName(clientDriver.aeronDirectoryName())
                .ingressChannel("aeron:udp?term-length=64k")
                .ingressEndpoints(ClusterNodeConfig.ingressEndpoints(HOSTNAMES, PORT_BASE))
                .egressChannel("aeron:udp?endpoint=localhost:0")
                .egressListener((clusterSessionId, timestamp, buffer, offset, length, header) ->
                    onEgress(buffer, offset)));
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(100);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void awaitEgress(final BooleanSupplier until) {
        await(() -> {
            try {
                if (!client.isClosed()) {
                    client.pollEgress();
                }
            } catch (final Exception ignore) {
                // election window; awaited condition decides
            }
            return until.getAsBoolean();
        });
    }

    private long countKind(final byte kind) {
        return acks.stream().filter(a -> a[1] == kind).count();
    }

    private List<Long> ackRefs(final byte kind) {
        return acks.stream().filter(a -> a[1] == kind).map(a -> a[0]).toList();
    }

    private void await(final BooleanSupplier condition) {
        final long deadline = System.currentTimeMillis() + 120_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("condition not met within 120s");
            }
            Thread.yield();
        }
    }

    // ----- ingress builders ------------------------------------------------------------------

    private void offerNewOrder(final long limitPx) {
        ingress.type = InputEvent.TYPE_ORDER_NEW;
        ingress.side = InputEvent.SIDE_BUY;
        ingress.orderRef = 0;
        ingress.accountId = ACCOUNT;
        ingress.securityId = SECURITY;
        ingress.qty = 10;
        ingress.limitPx = limitPx;
        ingress.priceTicks = 0;
        ingress.eventTimeMillis = 0;
        offerIngress();
    }

    private void offerPriceTick(final long px) {
        ingress.type = InputEvent.TYPE_PRICE_TICK;
        ingress.side = 0;
        ingress.orderRef = 0;
        ingress.accountId = 0;
        ingress.securityId = SECURITY;
        ingress.qty = 0;
        ingress.limitPx = 0;
        ingress.priceTicks = px;
        ingress.eventTimeMillis = 0;
        offerIngress();
    }

    private void offerAccountControl(final int accountId, final boolean enabled) {
        ingress.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        ingress.setControlEnabled(enabled);
        ingress.orderRef = 0;
        ingress.accountId = accountId;
        ingress.securityId = 0;
        ingress.qty = 0;
        ingress.limitPx = 0;
        ingress.setControlVersion(1L);
        ingress.eventTimeMillis = 0;
        offerIngress();
    }

    private void offerSecurityControl(final int securityId, final boolean enabled) {
        ingress.type = InputEvent.TYPE_SECURITY_CONTROL;
        ingress.setControlEnabled(enabled);
        ingress.orderRef = 0;
        ingress.accountId = 0;
        ingress.securityId = securityId;
        ingress.qty = 0;
        ingress.limitPx = 0;
        ingress.setControlVersion(2L);
        ingress.eventTimeMillis = 0;
        offerIngress();
    }
}
