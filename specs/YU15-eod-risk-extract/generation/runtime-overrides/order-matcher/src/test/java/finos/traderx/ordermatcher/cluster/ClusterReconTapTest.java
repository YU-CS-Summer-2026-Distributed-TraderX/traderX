package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import finos.traderx.ordermatcher.lmax.TradeBlotter;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit slice of the cluster-tier YU05 recon source (see {@link ClusterRecon}) — no cluster, no
 * archive. Two things are pinned here because both are silent when wrong:
 *
 * <ul>
 *   <li><b>The trade-id scheme.</b> The Spring tier mints {@code trd-09b-<seq>}; this tier mints
 *       {@code <tradeSeq>-B|S}, because {@code TradeNatsPublisher} publishes that and
 *       trade-processor keys its row on exactly it. Reusing the Spring scheme here would make
 *       EVERY projection row an {@code ORPHAN_IN_PROJECTION} — a recon that fails loudly about a
 *       system that is fine.</li>
 *   <li><b>The tap is inert when unset.</b> The sink is a seam in the deterministic apply path, so
 *       "no sink means the engine behaves exactly as before" is the property that keeps this a
 *       read-side addition rather than a core change.</li>
 * </ul>
 *
 * <p>The replay itself needs a live Aeron Archive and is proven on the rig by
 * {@code scripts/proofs/yu05-recon.sh}; what is provable without one is that the shadow drives the
 * REAL apply path — which is the same {@code onSessionMessage(null, ts, …)} entry every test in
 * {@code ClusterSnapshotCodecTest} already uses.
 */
class ClusterReconTapTest {
    private static final long PX = 1_000_000L;
    private static final int MAKER = 11;
    private static final int TAKER = 12;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer ingress = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private final UnsafeBuffer symbolBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
    private long timestamp = 1_700_000_000_000L;

    @Test
    void tapMintsThisTiersTradeIdsNotTheSpringTiers() {
        final ClusterRecon recon = newRecon();
        final MatchingEngineClusteredService service = newServiceTappedInto(recon);
        final int securityId = registerSymbol(service, "NVDA");
        seed(service, securityId);

        // A crossing pair books BOTH sides: the maker's fill and the taker's, each with its own
        // trade sequence — which is why the projection holds 1-S and 2-B for one match.
        apply(service, order(MAKER, securityId, InputEvent.SIDE_SELL, 200 * PX, 25, 101L));
        apply(service, order(TAKER, securityId, InputEvent.SIDE_BUY, 200 * PX, 25, 102L));

        final List<TradeBlotter.TradeRecord> page = recon.liveSince(0, 100);
        assertFalse(page.isEmpty(), "a crossing pair must book trades into the forward window");
        for (final TradeBlotter.TradeRecord trade : page) {
            assertEquals(trade.tradeSeq() + (trade.side().equals("Buy") ? "-B" : "-S"), trade.id(),
                "trade id must be <tradeSeq>-<B|S>, the id trade-processor keys its row on");
            assertFalse(trade.id().startsWith("trd-09b-"),
                "the Spring tier's id scheme here would orphan every projection row");
            assertEquals("NVDA", trade.security(), "ticker must resolve through the committed symbol table");
            assertNotNull(trade.price());
        }
        assertTrue(page.stream().anyMatch(t -> t.id().endsWith("-B")), "the buy side is booked");
        assertTrue(page.stream().anyMatch(t -> t.id().endsWith("-S")), "the sell side is booked");
    }

    @Test
    void forwardWindowPagesStrictlyAfterTheCursor() {
        final ClusterRecon recon = newRecon();
        final MatchingEngineClusteredService service = newServiceTappedInto(recon);
        final int securityId = registerSymbol(service, "NVDA");
        seed(service, securityId);
        apply(service, order(MAKER, securityId, InputEvent.SIDE_SELL, 200 * PX, 25, 101L));
        apply(service, order(TAKER, securityId, InputEvent.SIDE_BUY, 200 * PX, 25, 102L));

        final List<TradeBlotter.TradeRecord> all = recon.liveSince(0, 100);
        assertTrue(all.size() >= 2, "expected both sides of the cross");
        final long first = all.get(0).tradeSeq();
        // ReconciliationService advances its cursor to the highest tradeSeq it saw and asks for
        // strictly-greater; a page that re-served the cursor row would double-count every sweep.
        assertTrue(recon.liveSince(first, 100).stream().noneMatch(t -> t.tradeSeq() <= first),
            "sinceSeq is exclusive");
        assertEquals(all.size() - 1, recon.liveSince(first, 100).size());
    }

    @Test
    void unsetTapLeavesTheApplyPathUntouched() {
        final MatchingEngineClusteredService tapped = new MatchingEngineClusteredService();
        final MatchingEngineClusteredService plain = new MatchingEngineClusteredService();
        tapped.initEngine();
        plain.initEngine();
        final List<Byte> seen = new ArrayList<>();
        tapped.outputSink(out -> seen.add(out.kind));

        for (final MatchingEngineClusteredService service : List.of(tapped, plain)) {
            final int securityId = registerSymbol(service, "NVDA");
            seed(service, securityId);
            apply(service, order(MAKER, securityId, InputEvent.SIDE_SELL, 200 * PX, 25, 101L));
            apply(service, order(TAKER, securityId, InputEvent.SIDE_BUY, 200 * PX, 25, 102L));
        }

        assertFalse(seen.isEmpty(), "the tapped service must have fed its sink");
        assertTrue(seen.contains(OutputEvent.KIND_TRADE_BOOKED));
        // Same inputs, same committed state: the tap observes, it never participates.
        assertEquals(plain.appliedSeq(), tapped.appliedSeq());
        assertEquals(plain.nextOrderRef(), tapped.nextOrderRef());
        assertEquals(plain.engine().tradeCounter(), tapped.engine().tradeCounter());
        assertEquals(plain.engine().recoveryDigest().orderHash(),
            tapped.engine().recoveryDigest().orderHash());
        assertEquals(plain.engine().recoveryDigest().positionHash(),
            tapped.engine().recoveryDigest().positionHash());
    }

    // ----- harness ----------------------------------------------------------------------------

    private ClusterRecon newRecon() {
        return new ClusterRecon(new File("/nonexistent"), "/nonexistent", "7", 1000, 1000, 1000);
    }

    private MatchingEngineClusteredService newServiceTappedInto(final ClusterRecon recon) {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        service.outputSink(out -> recon.onLiveOutput(out, service.tickerFor(out.securityId)));
        return service;
    }

    private void seed(final MatchingEngineClusteredService service, final int securityId) {
        apply(service, accountControl(MAKER));
        apply(service, accountControl(TAKER));
        apply(service, securityControl(securityId));
        apply(service, priceTick(securityId, 200 * PX));
    }

    private int registerSymbol(final MatchingEngineClusteredService service, final String ticker) {
        codec.encodeSymbolRegister(symbolBuffer, 0, ++timestamp, ticker);
        service.onSessionMessage(null, timestamp, symbolBuffer, 0,
            AeronReplicationCodec.SYMBOL_BYTES, null);
        final int id = service.symbolIdFor(ticker);
        assertTrue(id >= 0, "registration must assign an id for " + ticker);
        return id;
    }

    private void apply(final MatchingEngineClusteredService service, final InputEvent event) {
        codec.encodeInput(ingress, 0, event, 0, 0, 0);
        service.onSessionMessage(null, ++timestamp, ingress, 0,
            AeronReplicationCodec.INPUT_BYTES, null);
    }

    private InputEvent order(final int accountId, final int securityId, final byte side,
                             final long limitPx, final int qty, final long clientOrderKey) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.side = side;
        e.accountId = accountId;
        e.securityId = securityId;
        e.qty = qty;
        e.limitPx = limitPx;
        e.priceTicks = clientOrderKey;
        return e;
    }

    private InputEvent accountControl(final int accountId) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        e.accountId = accountId;
        e.setControlEnabled(true);
        e.setControlVersion(1L);
        return e;
    }

    private InputEvent securityControl(final int securityId) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SECURITY_CONTROL;
        e.securityId = securityId;
        e.setControlEnabled(true);
        e.setControlVersion(1L);
        return e;
    }

    private InputEvent priceTick(final int securityId, final long px) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK;
        e.securityId = securityId;
        e.priceTicks = px;
        return e;
    }
}
