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

/** Real three-member Aeron consensus, retained snapshot plus tail, and immutable admission identity.
 * All drivers and storage are disposable test-owned resources; no retained rig is contacted. */
@Timeout(300)
class RunIdentityConsensusTest {
    private static final long PX = 1_000_000L;
    private static final int SECURITY = 1;
    private static final int ACCOUNT = 11;
    private static final int COUNTER = 12; // counterparty: the aggressing opposite side
    private static final int PORT_BASE = 22800;
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

    private RunDescriptor descriptor;
    private byte[] descriptorBytes;

    @Test
    void retainedSnapshotTailAndRestartKeepIdentityAndFrozenAdmission() throws Exception {
        descriptorBytes=("{\"schema\":\"traderx.run.v1\",\"epoch\":\"consensus_a\","
            +"\"eventIdScheme\":\"epoch-v1\",\"storageLineage\":\"test-storage-a\","
            +"\"projectionScope\":\"consensus-a\",\"adoptionEvidenceSha256\":null}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var expected=tempDir.resolve("expected.json");java.nio.file.Files.write(expected,descriptorBytes);
        descriptor=RunDescriptor.read(expected);
        for(int i=0;i<3;i++) launch(i,true);
        int leader=awaitLeader(-1);connectClient();
        offerNewOrder(100*PX);
        awaitEgress(()->countKind(OutputEvent.KIND_ORDER_REJECTED)==1);
        assertEquals(1,nodes[leader].service.nextOrderRef(),"unbound admission must not consume an order id");
        runControl(1);await(()->allPhase(1));
        registerSymbol("RESERVED");registerSymbol("IBM");
        await(()->java.util.Arrays.stream(nodes).allMatch(n->n.service.symbolIdFor("IBM")==SECURITY));
        offerAccountControl(ACCOUNT,true);offerAccountControl(COUNTER,true);
        offerSecurityControl(SECURITY,true);offerPriceTick(150*PX);
        runControl(2);await(()->allPhase(2));
        acks.clear();offerNewOrder(100*PX);offerSellOrder(COUNTER,100*PX,10);
        awaitEgress(()->countKind(OutputEvent.KIND_TRADE_BOOKED)==2);
        await(()->allTrades(2));
        offerNewOrder(100*PX);awaitEgress(()->countKind(OutputEvent.KIND_ORDER_ACCEPTED)>=3);
        takeSnapshot(leader);
        acks.clear();offerSellOrder(COUNTER,100*PX,10);
        awaitEgress(()->countKind(OutputEvent.KIND_TRADE_BOOKED)==2);await(()->allTrades(4));
        int follower=(leader+1)%3;
        stop(follower);launch(follower,false);awaitCatchUp(follower);
        assertClusterStateEquality(follower,leader);
        assertEquals(descriptor.hash(),nodes[follower].service.runDescriptor().hash());
        assertEquals(4,nodes[follower].service.engine().tradeCounter(),"snapshot had two legs, retained tail has two more");
        assertEquals("e1-consensus_a-4-S",nodes[follower].service.runDescriptor().tradeId(4,(byte)1));
        runControl(3);await(()->allPhase(3));
        long frozen=nodes[leader].service.frozenRunSequence();assertTrue(frozen>0);
        long next=nodes[leader].service.nextOrderRef();
        acks.clear();offerNewOrder(100*PX);awaitEgress(()->countKind(OutputEvent.KIND_ORDER_REJECTED)==1);
        assertEquals(next,nodes[leader].service.nextOrderRef());assertEquals(4,nodes[leader].service.engine().tradeCounter());
        takeSnapshot(leader);stop(follower);launch(follower,false);awaitCatchUp(follower);
        assertEquals(3,nodes[follower].service.runPhase());assertEquals(frozen,nodes[follower].service.frozenRunSequence());
        runControl(2);awaitEgress(()->countKind(MatchingEngineClusteredService.KIND_RUN_CONTROL)>0);
        assertTrue(allPhase(3),"activation retry cannot reopen a permanently frozen run");
        await(()->java.util.Arrays.stream(nodes).allMatch(n->n.service.appliedSeq()==nodes[leader].service.appliedSeq()));
        assertClusterStateEquality(follower,leader);
        var recon=new ClusterRecon(tempDir.resolve("node-"+leader).toFile(),tempDir.resolve("aeron-"+leader).toString(),descriptor.epoch(),100,100,100);
        recon.runDescriptor(descriptor);
        var replay=recon.projectionEvents();
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var wire=mapper.valueToTree(replay);
        assertEquals(descriptor.hash(),wire.path("descriptorHash").asText());
        assertEquals(4,wire.path("shadowTradeCounter").asLong());
        int legs=0,updates=0;
        for(var emitted:wire.path("events")) {
            var payload=emitted.path("payload");assertEquals(descriptor.hash(),payload.path("runDescriptorHash").asText());
            if("TradeOrder".equals(emitted.path("type").asText())) {
                legs++;assertTrue(payload.path("id").asText().startsWith("e1-consensus_a-"));
                assertTrue(payload.path("sourceOrderId").asText().startsWith("consensus_a-"));
            } else {updates++;assertTrue(payload.path("consensusSequence").asLong()>0);}
        }
        assertEquals(4,legs);assertTrue(updates>=4);
        System.out.println("RI06_FROZEN_REPLAY_WITNESS tradeLegs="+legs+" orderUpdates="+updates+" messages="+wire.path("replayedMessages").asLong());
        System.out.println("RI06_CONSENSUS_WITNESS descriptor="+descriptor.hash()+" snapshotTrades=2 retainedTailTrades=2 frozen="+frozen+" finalTrades=4");
    }

    private void registerSymbol(String ticker) {
        var buffer=new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
        codec.encodeSymbolRegister(buffer,0,123,ticker);
        await(()->{client.pollEgress();return client.offer(buffer,0,buffer.capacity())>0;});
    }
    private boolean allPhase(int phase) {
        for(Node n:nodes) if(n==null || n.service.runPhase()!=phase) return false;
        return true;
    }
    private boolean allTrades(long count) {
        for(Node n:nodes) if(n==null || n.service.engine()==null || n.service.engine().tradeCounter()!=count) return false;
        return true;
    }
    private void runControl(int operation) {
        descriptor.control(ingress,(byte)operation);offerIngress();
    }

    // ----- harness ---------------------------------------------------------------------------

    private void launch(final int id, final boolean cleanStart) {
        final Node node = new Node(id);
        node.service = new MatchingEngineClusteredService();
        try {
            var base=tempDir.resolve("node-"+id);java.nio.file.Files.createDirectories(base);
            if(cleanStart) java.nio.file.Files.write(base.resolve(RunDescriptor.FILE),descriptorBytes,java.nio.file.StandardOpenOption.CREATE_NEW);
            node.service.runDescriptor(RunDescriptor.load(base,tempDir.resolve("expected.json").toString(),"consensus_a"));
        } catch(Exception ex) {throw new IllegalStateException(ex);}
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
        // appliedSeq increments before an order finishes applying. A subsequent sequenced
        // idempotent control is a fence: observing it proves every prior apply returned.
        final int leader=awaitLeader(-1);
        final long boundary=nodes[leader].service.appliedSeq()+1;
        final int phase=nodes[leader].service.runPhase();
        runControl(phase==3 ? 3 : 2);
        await(()->{
            for(Node n:nodes) if(n==null || n.service.engine()==null
                || n.service.appliedSeq()<boundary || n.service.runPhase()!=phase) return false;
            return true;
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
        // The engine-only blpSeq is -1 after an idle snapshot restore; consensus appliedSeq is durable.
        assertEquals(sb.appliedSeq(), sa.appliedSeq());
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
