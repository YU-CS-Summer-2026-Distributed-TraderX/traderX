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
 * Single-member cluster proof (SC-AC01) at workstream-2 completeness: the inherited
 * deterministic MatchingEngine + authoritative BlpRiskState hosted as a ClusteredService, with
 * risk control state seeded exclusively by sequenced control ingress (ADR-045). Proves across
 * snapshot+tail recovery and snapshot+ZERO-tail recovery (the parent state's defect-exposing
 * shape): strict no-ID-reuse, trade-counter continuity, reservation and executed-exposure
 * continuity, and an idempotent client-key retry answered with the original order after two
 * recoveries.
 */
@Timeout(120)
class AeronClusterSpikeTest {
    private static final long PX = 1_000_000L;
    private static final int SECURITY = 1;
    private static final int ACCOUNT = 11;
    private static final int COUNTER = 12; // counterparty: the resting/aggressing opposite side
    private static final long CLIENT_KEY = 0x5EEDF00DL;
    private static final String CLUSTER_MEMBERS =
        "0,localhost:21610,localhost:21611,localhost:21612,localhost:21613,localhost:21614";
    private static final String INGRESS_CHANNEL = "aeron:udp?endpoint=localhost:21610|term-length=64k";

    @TempDir
    Path tempDir;

    private ClusteredMediaDriver clusteredMediaDriver;
    private ClusteredServiceContainer container;
    private MatchingEngineClusteredService service;
    private AeronCluster client;
    private File tapDir;          // non-null only for the analytical-capture test (brief 06)
    private KdbTapWriter tap;

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
        if (tap != null) {
            tap.stop();
        }
    }

    /**
     * Brief 06: the leader-side analytical tap captures the session a real cluster applied —
     * through consensus, not around it — and what lands on disk agrees with the deterministic
     * engine's own trade counter. The capture is a CSV the kdb layer (txstore.q) loads directly.
     *
     * <p>What this is NOT: a durability or recovery claim. The Aeron Archive stays the
     * authoritative journal; this file is off-consensus, best-effort and droppable, and nothing
     * here is read back by the state machine.
     */
    @Test
    void leaderTapCapturesTheAppliedSessionForKdb(@TempDir final File capture) throws Exception {
        // KDB_CAPTURE_FIXTURE_DIR regenerates the committed txselfcheck fixture from this very
        // run, so the q layer's regression gate is fed by a real cluster rather than by hand.
        final String fixture = System.getenv("KDB_CAPTURE_FIXTURE_DIR");
        tapDir = fixture == null || fixture.isBlank() ? capture : new File(fixture);
        launchNode(true);
        connectClient();

        offerAccountControl(ACCOUNT, true);
        offerAccountControl(COUNTER, true);
        offerSecurityControl(SECURITY, true);
        offerPriceTick(150 * PX);
        offerNewOrder(100 * PX, 0L);            // ref 1: rests
        offerSellOrder(COUNTER, 100 * PX, 10);  // ref 2: crosses it — a fill on each side
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 2
            && countKind(OutputEvent.KIND_ORDER_FILLED) == 2);

        // The tap is asynchronous BY DESIGN — apply never waits for it — so the test waits for
        // the writer, which is the one place this asymmetry is allowed to cost anything.
        await(() -> tap.captured() >= 6);
        tap.stop();

        final List<String> orders = java.nio.file.Files.readAllLines(
            new File(tapDir, "txorder-9-spike.csv").toPath());
        final List<String> trades = java.nio.file.Files.readAllLines(
            new File(tapDir, "txtrade-9-spike.csv").toPath());
        assertEquals(0, tap.dropped(), "nothing may be dropped at this volume");
        assertEquals(0, tap.errors(), "no write may fail");

        // Cross-check against the deterministic engine rather than against the tap itself: the
        // analytical store must agree with consensus state or it is worse than not having it.
        assertEquals(service.engine().tradeCounter(), trades.size() - 1,
            "every trade the engine booked must appear exactly once in the capture");
        assertEquals(4, orders.size() - 1, "two accepts and two fills");
        assertTrue(orders.get(1).contains(",1,11,#1,B,10,"), orders.get(1));  // ref 1 accepted, buy
        assertTrue(orders.get(4).contains(",FILLED,100.000000,10,"), orders.get(4));
        assertTrue(trades.get(1).contains(",11,#1,B,10,100.000000,"), trades.get(1));
        assertTrue(trades.get(2).contains(",12,#1,S,10,100.000000,"), trades.get(2));

        // Consensus order is the capture's order: seq is non-decreasing down the file, which is
        // what makes analytical playback a faithful replay of what the engine decided.
        long previous = -1;
        for (final String row : orders.subList(1, orders.size())) {
            final long seq = Long.parseLong(row.substring(0, row.indexOf(',')));
            assertTrue(seq >= previous, "capture must stay in consensus order: " + row);
            previous = seq;
        }
    }

    @Test
    void completeStateSurvivesSnapshotAndZeroTailRecoveryWithoutIdReuse() {
        launchNode(true);
        connectClient();

        // Risk control state enters ONLY as sequenced control ingress (ADR-045): without these
        // events every order would be rejected UNKNOWN_ACCOUNT / UNKNOWN_SECURITY.
        offerAccountControl(ACCOUNT, true);
        offerAccountControl(COUNTER, true);
        offerSecurityControl(SECURITY, true);

        // Live book (YU13 crossing): a tick seeds the mark (it never fills), three resting buys at
        // 100 by ACCOUNT (ref 2 carrying an idempotency key), then a counterparty SELL at 100 that
        // crosses the oldest resting buy (ref 1) — booking a trade on each side (tradeSeq 1 and 2).
        offerPriceTick(150 * PX);
        offerNewOrder(100 * PX, 0L);            // ref 1: ACCOUNT buy, rests (crossed below)
        offerNewOrder(100 * PX, CLIENT_KEY);    // ref 2: the key retried after both recoveries
        offerNewOrder(100 * PX, 0L);            // ref 3: ACCOUNT buy, rests
        offerSellOrder(COUNTER, 100 * PX, 10);  // ref 4: crosses ref 1 at the resting price 100
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 4
            && countKind(OutputEvent.KIND_ORDER_FILLED) == 2);
        // Aggressor ref 4's create-ack precedes the two fills; the resting side (ref 1) fills first.
        assertEquals(List.of(1L, 2L, 3L, 4L, 1L, 4L), acceptedOrFilledRefs());
        assertEquals(2 * 10 * 100 * PX, service.risk().reservedNotional(ACCOUNT),
            "the two still-resting buys (refs 2, 3) reserve exactly their notional");
        assertEquals(10 * 100 * PX, service.risk().executedNotional(ACCOUNT),
            "ref 1's fill converted its reservation into executed exposure at the resting price");
        assertEquals(10 * 100 * PX, service.risk().executedNotional(COUNTER),
            "the aggressor's fill executed exactly its notional");

        // Snapshot 1 binds the complete state — generators, book, risk, idempotency — at the
        // applied position; the two post-snapshot orders exist only in the committed log tail.
        takeSnapshot();
        acks.clear();
        offerNewOrder(100 * PX, 0L);
        offerNewOrder(100 * PX, 0L);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 2);
        assertEquals(List.of(5L, 6L), acceptedOrFilledRefs());

        // Recovery 1: snapshot + log tail. Resume strictly after the boundary, re-assign 5..6
        // identically during replay, and issue strictly beyond them.
        restartNode();
        assertEquals(5, service.lastLoadedNextOrderRef(), "snapshot must carry the generator");
        connectClient();
        offerNewOrder(100 * PX, 0L);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 1);
        assertEquals(List.of(7L), acceptedOrFilledRefs());
        assertEquals(5 * 10 * 100 * PX, service.risk().reservedNotional(ACCOUNT),
            "reservations rebuilt from snapshotted resting buys (2,3), replayed tail (5,6), and ref 7");

        // Snapshot 2 then recovery with ZERO log tail — the parent state's defect-exposing case:
        // every warm value came from the snapshot alone.
        takeSnapshot();
        restartNode();
        assertEquals(8, service.lastLoadedNextOrderRef(), "zero-tail recovery restores the generator");
        connectClient();
        assertEquals(5 * 10 * 100 * PX, service.risk().reservedNotional(ACCOUNT),
            "reservations rebuilt from the snapshot alone");
        assertEquals(10 * 100 * PX, service.risk().executedNotional(ACCOUNT),
            "executed exposure survives both recoveries");

        // Idempotent retry of the pre-snapshot-1 key: answered with the ORIGINAL order (ref 2),
        // creating nothing and reserving nothing — after two recoveries. The duplicate still
        // consumes one generator value deterministically, so the next fresh order takes 9.
        acks.clear();
        offerNewOrder(100 * PX, CLIENT_KEY);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 1);
        assertEquals(List.of(2L), acceptedOrFilledRefs());
        assertEquals(5 * 10 * 100 * PX, service.risk().reservedNotional(ACCOUNT),
            "a replayed duplicate reserves nothing");

        acks.clear();
        offerNewOrder(100 * PX, 0L);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 1);
        assertEquals(List.of(9L), acceptedOrFilledRefs());

        // Book recovered completely: refs 1..9 ever issued (8 consumed by the duplicate); refs 1
        // and 4 filled pre-snapshot, so ACCOUNT's buys 2,3,5,6,7,9 remain open — six orders.
        assertEquals(6, service.engine().openOrderTuples().size());

        // One large counterparty SELL crosses every remaining resting buy (ref 10), booking a
        // trade on each side per match step; trade sequencing continues past the pre-snapshot
        // cross (ends at 14 = 2 + 6 levels x 2 sides), proving the trade counter survived both
        // recoveries and never restarted.
        acks.clear();
        offerSellOrder(COUNTER, 100 * PX, 60);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_FILLED) == 7); // 6 resting + the aggressor
        final long maxTradeSeq = acks.stream()
            .filter(a -> a[1] == OutputEvent.KIND_TRADE_BOOKED)
            .mapToLong(a -> a[2]).max().orElse(-1);
        assertEquals(14, maxTradeSeq, "trade counter continues across recoveries, never restarts");
        assertEquals(0, service.engine().openOrderTuples().size());
        assertEquals(0L, service.risk().reservedNotional(ACCOUNT), "all reservations consumed by fills");
    }

    // ----- cluster harness -------------------------------------------------------------------

    private void launchNode(final boolean cleanStart) {
        final String aeronDir = new File(tempDir.toFile(), "aeron").getAbsolutePath();
        final File clusterDir = new File(tempDir.toFile(), "cluster");
        final File archiveDir = new File(tempDir.toFile(), "archive");

        service = new MatchingEngineClusteredService();
        if (tapDir != null) {
            // Analytical capture (brief 06). Enabled only for the capture test; every other test
            // in this class runs with it null, which is also the production default.
            tap = new KdbTapWriter(tapDir, "9", "spike", 1024);
            tap.start();
            service.kdbTap(tap);
        }
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

    private void offerNewOrder(final long limitPx, final long clientOrderKey) {
        offerLimitOrder(ACCOUNT, InputEvent.SIDE_BUY, limitPx, 10, clientOrderKey);
    }

    private void offerSellOrder(final int accountId, final long limitPx, final int qty) {
        offerLimitOrder(accountId, InputEvent.SIDE_SELL, limitPx, qty, 0L);
    }

    private void offerLimitOrder(final int accountId, final byte side, final long limitPx,
                                 final int qty, final long clientOrderKey) {
        ingress.type = InputEvent.TYPE_ORDER_NEW;
        ingress.side = side;
        ingress.orderRef = 0; // the cluster service owns the generator (ADR-046)
        ingress.accountId = accountId;
        ingress.securityId = SECURITY;
        ingress.qty = qty;
        ingress.limitPx = limitPx;
        ingress.priceTicks = clientOrderKey; // type-discriminated slot: idempotency key
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
