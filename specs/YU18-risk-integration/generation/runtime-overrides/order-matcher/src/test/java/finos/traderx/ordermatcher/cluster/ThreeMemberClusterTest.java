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
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    private static final int COUNTER = 12; // counterparty: the aggressing opposite side
    private static final int PORT_BASE = TestPorts.freeBase(3);
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
    private String bridgeUrl; // NATS for the members' bridges; null = no bridge (the default)

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
        offerAccountControl(COUNTER, true);
        offerSecurityControl(SECURITY, true);
        offerPriceTick(150 * PX);
        offerNewOrder(100 * PX);
        offerNewOrder(100 * PX);
        offerNewOrder(100 * PX);
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_ACCEPTED) == 3);
        assertEquals(List.of(1L, 2L, 3L), ackRefs(OutputEvent.KIND_ORDER_ACCEPTED));
        assertCommitSegmentIsSane(leader1);

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

        // Fill everything through the current leader: one large counterparty SELL (ref 8) crosses
        // all seven resting buys, booking a trade on each side per match step. Trade sequencing is
        // one unbroken lineage — 7 levels x 2 sides = 14 — across two failovers and a wiped-member
        // recovery.
        acks.clear();
        offerSellOrder(COUNTER, 100 * PX, 70);
        // Await the TRADE_BOOKED acks as well: they can trail the 8th FILLED ack on egress, and reading the max
        // before the 14th arrived failed this under full-suite load ("expected 14 but was 13").
        awaitEgress(() -> countKind(OutputEvent.KIND_ORDER_FILLED) == 8   // 7 resting + the aggressor
            && countKind(OutputEvent.KIND_TRADE_BOOKED) == 14);
        final long maxTradeSeq = acks.stream()
            .filter(a -> a[1] == OutputEvent.KIND_TRADE_BOOKED)
            .mapToLong(a -> a[2]).max().orElse(-1);
        assertEquals(14, maxTradeSeq);
    }

    /**
     * FR-DRS-37: every trade the cluster applies reaches TRADERX_FEED exactly once across a leader
     * kill. Production 2026-11-08: members 80,888 trades, `/trades` 80,884; the four missing were
     * applied in the second after the leader died. The bridge published only on the leader, live,
     * so what the dying leader applied but had not got stored was never sent by anyone. Here the
     * leader's bridge stops before it publishes the last three matches (in flight at the kill), the
     * leader dies, and the new leader must publish exactly those six sides (one tradeSeq per side): no gap, no duplicate.
     */
    @Test
    void tradesInFlightAtALeaderKillReachTheStreamExactlyOnce() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "needs Docker for NATS JetStream");
        try (GenericContainer<?> nats = new GenericContainer<>("nats:2.14-alpine").withCommand("-js")
                .withExposedPorts(4222).waitingFor(Wait.forLogMessage(".*Server is ready.*", 1))) {
            nats.start();
            bridgeUrl = "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222);
            try (Connection nc = Nats.connect(bridgeUrl)) {
                nc.jetStreamManagement().addStream(StreamConfiguration.builder().name("TRADERX_FEED")
                    .subjects("/trades", "/orders").storageType(StorageType.Memory).build());
                for (int i = 0; i < 3; i++) {
                    launch(i, true);
                }
                final int leader1 = awaitLeader(-1);
                connectClient();
                // The bridge publishes only a trade whose security has a ticker: ids 0 and 1 (SECURITY).
                offerSymbolRegister("AAA");
                offerSymbolRegister("IBM");
                offerAccountControl(ACCOUNT, true);
                offerAccountControl(COUNTER, true);
                offerSecurityControl(SECURITY, true);
                offerPriceTick(150 * PX);

                // Two matches the live leader publishes: the stream's last stored trade is the cut.
                offerNewOrder(100 * PX);
                offerNewOrder(100 * PX);
                offerSellOrder(COUNTER, 100 * PX, 20);
                awaitEgress(() -> countKind(OutputEvent.KIND_TRADE_BOOKED) == 4);
                assertEquals(4, streamTradeIds(nc, 4).size(), "the live leader published round 1");

                // The dying leader: its bridge stops, so the next three matches it applies are never
                // published by it. The followers apply them too.
                nodes[leader1].service.onTerminate(null);
                offerNewOrder(100 * PX);
                offerNewOrder(100 * PX);
                offerNewOrder(100 * PX);
                offerSellOrder(COUNTER, 100 * PX, 30);
                awaitEgress(() -> countKind(OutputEvent.KIND_TRADE_BOOKED) == 10);
                stop(leader1);
                awaitLeader(leader1);

                // One tradeSeq per side: five matches book sides 1..10, in apply order.
                final List<String> ids = streamTradeIds(nc, 10);
                assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L),
                    ids.stream().map(i -> Long.parseLong(i.substring(0, i.indexOf('-')))).toList(),
                    "every applied trade side on the stream exactly once, in order: " + ids);
            }
        }
    }

    /** The trade ids on {@code /trades} in stream order, read once {@code want} are stored (or 20 s
     *  pass) and a further second has gone by, so a late duplicate is counted too. */
    private static List<String> streamTradeIds(final Connection nc, final int want) throws Exception {
        final long deadline = System.currentTimeMillis() + 20_000;
        while (readTradeIds(nc).size() < want && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        Thread.sleep(1_000);
        return readTradeIds(nc);
    }

    private static List<String> readTradeIds(final Connection nc) throws Exception {
        final Pattern id = Pattern.compile("\"id\":\"([^\"]+)\"");
        final List<String> out = new ArrayList<>();
        final JetStreamSubscription sub = nc.jetStream().subscribe("/trades",
            PushSubscribeOptions.builder().stream("TRADERX_FEED").ordered(true).build());
        for (Message m; (m = sub.nextMessage(Duration.ofMillis(300))) != null; ) {
            final Matcher x = id.matcher(new String(m.getData(), java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(x.find());
            out.add(x.group(1));
        }
        sub.unsubscribe();
        return out;
    }

    // ----- harness ---------------------------------------------------------------------------

    private void launch(final int id, final boolean cleanStart) {
        final Node node = new Node(id);
        node.service = new MatchingEngineClusteredService();
        if (bridgeUrl != null) {
            node.service.bridgeUrl = bridgeUrl;
        }
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

    /**
     * A member's service seeing LEADER is not the client seeing it: the NewLeader event reaches the
     * client after the new leader's election closes. Offered in between, an order clears into the
     * DEAD leader's still-connected ingress publication and is lost — Aeron ingress is not
     * delivered across a failover; production gateways answer such orders ambiguous for a deduped
     * resend (ClusterGatewayMain#onNewLeader). Only a fast election opens that window (the dead
     * leader's end-of-stream reached the followers: sub-second, vs the 10 s heartbeat timeout),
     * and load made it common: three full-suite failures on 2026-10-30, every one an egress wait
     * after a sub-second failover. So a failover is over for the test once its client is on the
     * new leader, which still fails here if the client never gets there.
     */
    private int awaitLeader(final int excluded) {
        final int[] leader = { -1 };
        await("a leader other than member " + excluded, () -> {
            for (final Node node : nodes) {
                if (node != null && node.id != excluded && node.service.role() == Cluster.Role.LEADER) {
                    leader[0] = node.id;
                    return true;
                }
            }
            return false;
        });
        if (client != null) {
            await("the client on new leader " + leader[0], () -> {
                try {
                    if (client.isClosed()) {
                        connectClientKeepingAcks();
                    } else {
                        client.pollEgress();
                    }
                } catch (final Exception ignore) {
                    // election window; the next poll retries
                }
                return !client.isClosed() && client.leaderMemberId() == leader[0];
            });
        }
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
        await("every member's snapshot", () -> {
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
        await("member " + id + " caught up", () -> {
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

    /**
     * LATENCY-02: when the decomposition is enabled, the leader's commit segment must be sane —
     * which is really a check that its two ends read the SAME clock. The interval subtracts the
     * cluster's sequencing timestamp from a clock read at apply; if those two ever came from
     * different sources (epoch vs. JVM-uptime nanos), every sample would be wildly negative — and so
     * silently dropped, leaving count 0 — or wildly large. Both bounds together catch that, on the
     * ms clock and under {@code CLUSTER_CLOCK=nanos} alike. No-op when instrumentation is off.
     */
    private void assertCommitSegmentIsSane(final int leader) {
        final LeaderApplyLatency m = nodes[leader].service.leaderLatency();
        if (m == null) {
            return; // LATENCY_DECOMP unset — instrumentation absent by design
        }
        final String d = m.dump();
        assertTrue(d.contains("segment=\"commit\"} 0\n") == false,
            () -> "leader recorded no commit samples — the interval's two clock ends disagree:\n" + d);
        final int i = d.indexOf("segment=\"commit\",pct=\"p999\"} ");
        final double p999us = Double.parseDouble(
            d.substring(i + 28, d.indexOf('\n', i)).trim());
        assertTrue(p999us < 1_000_000.0,
            () -> "commit p99.9 of " + p999us + "us is not a latency — clock sources disagree:\n" + d);
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
        await("egress acks", () -> {
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

    /** {@code what} names the wait: Gradle prints only the message and this line, not the caller. */
    private void await(final String what, final BooleanSupplier condition) {
        final long deadline = System.currentTimeMillis() + 120_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("not met within 120s: " + what);
            }
            Thread.yield();
        }
    }

    // ----- ingress builders ------------------------------------------------------------------

    private void offerNewOrder(final long limitPx) {
        offerLimitOrder(ACCOUNT, InputEvent.SIDE_BUY, limitPx, 10);
    }

    private void offerSellOrder(final int accountId, final long limitPx, final int qty) {
        offerLimitOrder(accountId, InputEvent.SIDE_SELL, limitPx, qty);
    }

    private void offerLimitOrder(final int accountId, final byte side, final long limitPx, final int qty) {
        ingress.type = InputEvent.TYPE_ORDER_NEW;
        ingress.side = side;
        ingress.orderRef = 0;
        ingress.accountId = accountId;
        ingress.securityId = SECURITY;
        ingress.qty = qty;
        ingress.limitPx = limitPx;
        ingress.priceTicks = 0;
        ingress.eventTimeMillis = 0;
        offerIngress();
    }

    private void offerSymbolRegister(final String ticker) {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
        codec.encodeSymbolRegister(buffer, 0, System.nanoTime(), ticker);
        await("symbol " + ticker + " offered", () -> {
            client.pollEgress();
            return client.offer(buffer, 0, AeronReplicationCodec.SYMBOL_BYTES) > 0;
        });
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
