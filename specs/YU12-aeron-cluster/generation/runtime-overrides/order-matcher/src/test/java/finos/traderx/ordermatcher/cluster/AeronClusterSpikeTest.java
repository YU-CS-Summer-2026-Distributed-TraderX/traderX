package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.cluster.ClusterTool;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.CloseHelper;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.PrintStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-1 single-member cluster proof (SC-AC01): the inherited deterministic MatchingEngine
 * hosted as a ClusteredService, orders round-tripping the consensus log, and the strict
 * no-ID-reuse invariant across two recoveries — snapshot + log tail, then snapshot + ZERO tail
 * (the exact shape that exposed the parent state's nextOrderRef defect).
 */
@Timeout(120)
class AeronClusterSpikeTest {
    private static final long PX = 1_000_000L;
    private static final int SECURITY = 1;
    private static final int ACCOUNT = 11;
    private static final String CLUSTER_MEMBERS =
        "0,localhost:21610,localhost:21611,localhost:21612,localhost:21613,localhost:21614";
    private static final String INGRESS_CHANNEL = "aeron:udp?endpoint=localhost:21610|term-length=64k";

    @TempDir
    Path tempDir;

    private ClusteredMediaDriver clusteredMediaDriver;
    private ClusteredServiceContainer container;
    private MatchingEngineClusteredService service;
    private AeronCluster client;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final InputEvent ingress = new InputEvent();
    private final UnsafeBuffer ingressBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final List<long[]> acks = new ArrayList<>(); // {orderRef, kind, tradeSeq}

    private final EgressListener egressListener = new EgressListener() {
        @Override
        public void onMessage(final long clusterSessionId, final long timestamp,
                              final DirectBuffer buffer, final int offset, final int length,
                              final Header header) {
            acks.add(new long[] {
                buffer.getInt(offset + 8), buffer.getByte(offset + 12), buffer.getLong(offset + 13) });
        }
    };

    @AfterEach
    void tearDown() {
        CloseHelper.quietCloseAll(client, container, clusteredMediaDriver);
    }

    @Test
    void ordersSurviveSnapshotAndZeroTailRecoveryWithoutIdReuse() {
        launchNode(true);
        connectClient();

        // Live book: a tick that leaves buy-limit-100 orders resting, three resting orders,
        // then one marketable order that fills and books trade 1 — all through the consensus log.
        offerPriceTick(150 * PX);
        offerNewOrder(100 * PX);
        offerNewOrder(100 * PX);
        offerNewOrder(100 * PX);
        offerNewOrder(200 * PX); // in the money at 150: create-ack then a full fill, tradeSeq 1
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 4
            && countKind(OutputEvent.KIND_ORDER_FILLED) == 1);
        assertEquals(List.of(1L, 2L, 3L, 4L, 4L), acceptedOrFilledRefs());

        // Snapshot 1 binds the complete state — including the generators — at the applied position.
        takeSnapshot();
        assertEquals(1, service.snapshotsTaken());

        // Post-snapshot orders: they exist only in the committed log tail.
        acks.clear();
        offerNewOrder(100 * PX);
        offerNewOrder(100 * PX);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 2);
        assertEquals(List.of(5L, 6L), acceptedOrFilledRefs());

        // Recovery 1: snapshot + log tail. The service must resume after the snapshot boundary,
        // re-assign refs 5..6 identically during replay, and issue strictly beyond them.
        restartNode();
        assertEquals(5, service.lastLoadedNextOrderRef(), "snapshot must carry the generator");
        connectClient();
        offerNewOrder(100 * PX);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 1);
        assertEquals(List.of(7L), acceptedOrFilledRefs());

        // Snapshot 2 then recovery with ZERO log tail — the parent state's defect-exposing case:
        // every warm order came from the snapshot alone, so a generator outside snapshotted
        // state would reissue ref 5 here.
        takeSnapshot();
        assertEquals(1, service.snapshotsTaken());
        restartNode();
        assertEquals(8, service.lastLoadedNextOrderRef(), "zero-tail recovery restores the generator");
        connectClient();
        offerNewOrder(100 * PX);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 1);
        assertEquals(List.of(8L), acceptedOrFilledRefs());

        // The book recovered completely: 8 orders ever issued, 7 still open (order 4 filled).
        assertEquals(7, service.engine().openOrderTuples().size());

        // A crossing tick fills every open order; trade sequencing must continue at 2..8,
        // proving the trade counter also survived both recoveries.
        offerPriceTick(90 * PX);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_FILLED) == 7);
        final long maxTradeSeq = acks.stream()
            .filter(a -> a[1] == OutputEvent.KIND_TRADE_BOOKED)
            .mapToLong(a -> a[2]).max().orElse(-1);
        assertEquals(8, maxTradeSeq, "trade counter continues across recoveries, never restarts");
        assertEquals(0, service.engine().openOrderTuples().size());
    }

    // ----- cluster harness -------------------------------------------------------------------

    private void launchNode(final boolean cleanStart) {
        final String aeronDir = new File(tempDir.toFile(), "aeron").getAbsolutePath();
        final File clusterDir = new File(tempDir.toFile(), "cluster");
        final File archiveDir = new File(tempDir.toFile(), "archive");

        service = new MatchingEngineClusteredService();
        clusteredMediaDriver = ClusteredMediaDriver.launch(
            new MediaDriver.Context()
                .aeronDirectoryName(aeronDir)
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(true)
                .dirDeleteOnStart(true),
            new Archive.Context()
                .aeronDirectoryName(aeronDir)
                .archiveDir(archiveDir)
                .controlChannel("aeron:udp?endpoint=localhost:21614|term-length=64k")
                .replicationChannel("aeron:udp?endpoint=localhost:0")
                .recordingEventsEnabled(false)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .deleteArchiveOnStart(cleanStart),
            new ConsensusModule.Context()
                .aeronDirectoryName(aeronDir)
                .clusterDir(clusterDir)
                .clusterMembers(CLUSTER_MEMBERS)
                .ingressChannel("aeron:udp?term-length=64k")
                .logChannel("aeron:ipc?term-length=256k")
                .replicationChannel("aeron:udp?endpoint=localhost:0")
                .deleteDirOnStart(cleanStart));
        container = ClusteredServiceContainer.launch(
            new ClusteredServiceContainer.Context()
                .aeronDirectoryName(aeronDir)
                .clusterDir(clusterDir)
                .clusteredService(service));
    }

    private void restartNode() {
        CloseHelper.quietCloseAll(client, container, clusteredMediaDriver);
        client = null;
        launchNode(false);
        // launch() returns before the service agent runs onStart; recovery is observable
        // through the volatile set at the end of the snapshot load.
        await(() -> service.lastLoadedNextOrderRef() != -1);
    }

    private void connectClient() {
        acks.clear();
        client = AeronCluster.connect(
            new AeronCluster.Context()
                .aeronDirectoryName(clusteredMediaDriver.mediaDriver().aeronDirectoryName())
                .ingressChannel(INGRESS_CHANNEL)
                .egressChannel("aeron:udp?endpoint=localhost:0")
                .egressListener(egressListener));
    }

    private void takeSnapshot() {
        final int before = service.snapshotsTaken();
        assertTrue(ClusterTool.snapshot(
            clusteredMediaDriver.consensusModule().context().clusterDir(),
            new PrintStream(OutputStream.nullOutputStream())), "snapshot trigger accepted");
        await(() -> service.snapshotsTaken() > before);
    }

    // ----- ingress/egress helpers ------------------------------------------------------------

    private void offerNewOrder(final long limitPx) {
        ingress.type = InputEvent.TYPE_ORDER_NEW;
        ingress.side = InputEvent.SIDE_BUY;
        ingress.orderRef = 0; // the cluster service owns the generator (ADR-046)
        ingress.accountId = ACCOUNT;
        ingress.securityId = SECURITY;
        ingress.qty = 10;
        ingress.limitPx = limitPx;
        ingress.priceTicks = 0;
        ingress.eventTimeMillis = 0; // overwritten with cluster time by the service
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

    private void offerIngress() {
        codec.encodeInput(ingressBuffer, 0, ingress, 0, 0, 0);
        await(() -> client.offer(ingressBuffer, 0, AeronReplicationCodec.INPUT_BYTES) > 0);
    }

    private void awaitEgress(final BooleanSupplier until) {
        await(() -> {
            client.pollEgress();
            return until.getAsBoolean();
        });
    }

    private long countKind(final byte kind) {
        return acks.stream().filter(a -> a[1] == kind).count();
    }

    private List<Long> acceptedOrFilledRefs() {
        return acks.stream()
            .filter(a -> a[1] == OutputEvent.KIND_ORDER_ACCEPTED || a[1] == OutputEvent.KIND_ORDER_FILLED
                || a[1] == OutputEvent.KIND_ORDER_PARTIALLY_FILLED)
            .map(a -> a[0])
            .toList();
    }

    private void await(final BooleanSupplier condition) {
        final long deadline = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("condition not met within 30s");
            }
            Thread.yield();
        }
    }
}
