package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.OutputEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.MemoryStoreFactory;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.Account;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecType;
import quickfix.field.HandlInst;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelRequest;
import quickfix.fix44.OrderStatusRequest;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Properties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-FIX05 (ADR-084, audit suspect 8): a fill after the direct ack reaches the FIX session that
 * owns the order as ExecutionReport ExecType=F. Two real QuickFIX/J sessions; the stub submitter
 * answers every order ACCEPTED (the engine's direct ack for a new order, fill or no fill), and the
 * feed messages are encoded by the production {@link OrderNatsPublisher}, so the acceptor's byte
 * scan reads exactly what the leader publishes.
 */
@Timeout(60)
class FixGatewayFillReportTest {
    private static final byte NEW = 0, PARTIAL = 1, FILLED = 2, CANCELED = 3;
    private FixGatewayAcceptor acceptor;
    private final List<SocketInitiator> initiators = new CopyOnWriteArrayList<>();
    private final OrderNatsPublisher encoder = new OrderNatsPublisher("", "/orders", "7", null, 16, 1);

    @AfterEach
    void tearDown() {
        initiators.forEach(SocketInitiator::stop);
        if (acceptor != null) {
            acceptor.stop();
        }
    }

    /** Refs 1, 2, ...; {@code beforeAck} runs between "sequenced" and "acked", where the feed can win. */
    private static final class StubSubmitter implements OrderSubmitter {
        final AtomicInteger nextRef = new AtomicInteger(1);
        volatile IntConsumer beforeAck = ref -> { };
        volatile ExecResult answer;   // when set, the engine's answer to every order
        volatile ExecResult cancelAnswer;   // when set, the engine's answer to every cancel

        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                      final char side, final int qty, final long limitPxTicks) {
            if (answer != null) {
                return answer;
            }
            final int ref = nextRef.getAndIncrement();
            beforeAck.accept(ref);
            return new ExecResult(true, ref, OutputEvent.KIND_ORDER_ACCEPTED);
        }

        @Override
        public ExecResult submitCancel(final long orderRef) {
            if (cancelAnswer != null) {
                return cancelAnswer;
            }
            return new ExecResult(true, orderRef, OutputEvent.KIND_ORDER_CANCELED);
        }
    }

    /** FR-BSP-02: holds every FIX NEW until the test answers it, in whatever order the test chooses. */
    private static final class GatedSubmitter implements OrderSubmitter {
        final List<String> arrived = new CopyOnWriteArrayList<>();
        final Map<String, CompletableFuture<ExecResult>> held = new ConcurrentHashMap<>();
        final AtomicInteger cancels = new AtomicInteger();

        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                      final char side, final int qty, final long limitPxTicks) {
            throw new AssertionError("FR-BSP-02: a FIX NEW is submitted without waiting");
        }

        @Override
        public CompletableFuture<ExecResult> submitOrderAsync(final String clOrdId, final int accountId,
                                                             final String ticker, final char side, final int qty,
                                                             final long limitPxTicks, final TypedOrder typed,
                                                             final Executor completions) {
            final String raw = clOrdId.substring(clOrdId.indexOf('\001') + 1);
            final CompletableFuture<ExecResult> f = new CompletableFuture<>();
            held.put(raw, f);
            arrived.add(raw);
            return f;
        }

        volatile ExecResult cancelAnswer;   // when set, the engine's answer to every cancel

        @Override
        public ExecResult submitCancel(final long orderRef) {
            cancels.incrementAndGet();
            return cancelAnswer != null ? cancelAnswer : new ExecResult(true, orderRef, OutputEvent.KIND_ORDER_CANCELED);
        }

        void answer(final String clOrdId, final int ref) {
            held.get(clOrdId).complete(ref == 0 ? null : new ExecResult(true, ref, OutputEvent.KIND_ORDER_ACCEPTED));
        }

        boolean awaitArrived(final int n) throws InterruptedException {
            for (int i = 0; i < 200 && arrived.size() < n; i++) {
                Thread.sleep(25);
            }
            return arrived.size() >= n;
        }
    }

    /**
     * FR-BSP-02: one session carries a window of orders at once. They reach the seam in arrival order, at most
     * {@link FixGatewayAcceptor#MAX_IN_FLIGHT} undecided (then the session stops being read); each is answered once,
     * in the order the engine decided them; an ambiguous one (null) gets no report and frees its slot.
     */
    @Test
    void aSessionPipelinesItsOrdersUpToItsBoundAndAnswersInDecisionOrder() throws Exception {
        final GatedSubmitter submitter = new GatedSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        final int bound = FixGatewayAcceptor.MAX_IN_FLIGHT;
        final int total = bound + 50;
        final List<String> sent = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            sent.add("p" + i);
            sendOrder(c, "p" + i, Side.BUY, 1, 50.0);
        }
        assertTrue(submitter.awaitArrived(bound), "the window never filled: " + submitter.arrived.size());
        Thread.sleep(300);
        assertEquals(bound, submitter.arrived.size(), "a full window stops the session being read");
        assertEquals(sent.subList(0, bound), submitter.arrived, "orders reach the seam in arrival order");
        assertEquals(0, c.reports.size(), "nothing is answered before it is decided");

        final List<String> window = new ArrayList<>(sent.subList(0, bound));
        Collections.reverse(window);
        for (final String id : window) {
            submitter.answer(id, "p7".equals(id) ? 0 : Integer.parseInt(id.substring(1)) + 1);   // p7 ambiguous
        }
        assertTrue(submitter.awaitArrived(total), "freed slots let the rest in");
        for (final String id : sent.subList(bound, total)) {
            submitter.answer(id, Integer.parseInt(id.substring(1)) + 1);
        }
        assertTrue(c.awaitCount(r -> exec(r) == ExecType.NEW, total - 1), "reports: " + c.reports.size());
        Thread.sleep(200);
        final List<String> answered = c.reports.stream().map(FixGatewayFillReportTest::cl).toList();
        assertEquals(total - 1, answered.size(), "each decided order answered once, the ambiguous one not at all");
        final List<String> expected = new ArrayList<>(window);
        expected.remove("p7");
        expected.addAll(sent.subList(bound, total));
        assertEquals(expected, answered, "answered in the order decided");
    }

    /** FR-BSP-02: a cancel by OrigClOrdID of an order still in flight waits for its answer, then finds it. */
    @Test
    void aCancelOfAnOrderInFlightWaitsForItsAnswer() throws Exception {
        final GatedSubmitter submitter = new GatedSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "w1", Side.BUY, 5, 50.0);
        assertTrue(submitter.awaitArrived(1));
        sendCancel(c, "w1-x", "w1");
        Thread.sleep(300);
        assertEquals(0, submitter.cancels.get(), "the cancel waits for the order it names");
        submitter.answer("w1", 41);
        assertNotNull(c.await(r -> is(r, "w1-x", ExecType.CANCELED)), "the cancel resolved the order once answered");
        assertEquals(1, submitter.cancels.get());
        assertEquals(List.of("w1", "w1-x"), c.reports.stream().map(FixGatewayFillReportTest::cl).toList(),
            "the New before the cancel");
    }

    /**
     * FR-FFR-15: fills that reach the gateway while their pipelined orders wait for answers are all reported once the
     * answers come, however many other orders' fills arrive meanwhile. Falsifiable: the 4,096-slot early ring this
     * replaced was overwritten by the 5,000 later fills, and none of the 100 was reported.
     */
    @Test
    void fillsThatBeatTheirPipelinedAnswersAreReportedWhateverArrivesMeanwhile() throws Exception {
        final GatedSubmitter submitter = new GatedSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        final int orders = 100;
        for (int i = 0; i < orders; i++) {
            sendOrder(c, "e" + i, Side.BUY, 1, 50.0);
        }
        assertTrue(submitter.awaitArrived(orders));
        for (int i = 0; i < orders; i++) {
            acceptor.onOrderUpdate(feed(1 + i, (byte) 0, 1, 0, FILLED, 50.0, 1));   // the fill beats the answer
        }
        for (int i = 0; i < 5_000; i++) {
            acceptor.onOrderUpdate(feed(100_001 + i, (byte) 1, 1, 0, FILLED, 50.0, 1));   // other orders' fills
        }
        for (int i = 0; i < orders; i++) {
            submitter.answer("e" + i, 1 + i);
        }
        assertTrue(c.awaitCount(r -> exec(r) == ExecType.TRADE, orders),
            "fill reports: " + c.count(r -> exec(r) == ExecType.TRADE) + " of " + orders);
        assertEquals(0, acceptor.fillsLost());
    }

    /**
     * FR-FFR-17: the venue's cancel of an order whose pipelined answer has not run yet (self-trade prevention cancels the
     * resting order in milliseconds; the 2026-11-10 run's answers took ~7 s) reaches the client, after its New, as an
     * unsolicited Canceled report. Falsifiable: the gateway dropped every update but a fill for an untracked order, and
     * reported none of 44M STP cancels.
     */
    @Test
    void aVenueCancelThatBeatsThePipelinedAnswerIsReportedAfterTheNew() throws Exception {
        final GatedSubmitter submitter = new GatedSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "v1", Side.BUY, 5, 50.0);
        assertTrue(submitter.awaitArrived(1));
        acceptor.onOrderUpdate(feed(1, (byte) 0, 5, 5, CANCELED, 0, 0));   // canceled by the venue before the answer
        submitter.answer("v1", 1);
        assertNotNull(c.await(r -> is(r, "v1", ExecType.CANCELED)), "the venue's cancel was not reported");
        assertEquals(List.of(ExecType.NEW, ExecType.CANCELED),
            c.reports.stream().map(FixGatewayFillReportTest::exec).toList(), "the New, then the Canceled");
        assertEquals(1, acceptor.endReports());
    }

    /** FR-FFR-17: a cancel the client asked for is answered once, by its OrderCancelRequest, not again as the venue's. */
    @Test
    void aClientCancelIsReportedOnceWhenTheVenueUpdateFollows() throws Exception {
        final GatedSubmitter submitter = new GatedSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "w2", Side.BUY, 5, 50.0);
        assertTrue(submitter.awaitArrived(1));
        submitter.answer("w2", 7);
        assertNotNull(c.await(r -> is(r, "w2", ExecType.NEW)));
        sendCancel(c, "w2-x", "w2");
        assertNotNull(c.await(r -> is(r, "w2-x", ExecType.CANCELED)));
        acceptor.onOrderUpdate(feed(7, (byte) 0, 5, 5, CANCELED, 0, 0));
        Thread.sleep(300);
        assertEquals(1, c.count(r -> exec(r) == ExecType.CANCELED), "one Canceled report");
        assertEquals(0, acceptor.endReports());
    }

    /** FR-FFR-17: a client cancel the engine refused (the venue ended the order first) does not mute the venue's end. */
    @Test
    void aRefusedClientCancelLeavesTheVenueCancelReported() throws Exception {
        final GatedSubmitter submitter = new GatedSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "w3", Side.BUY, 5, 50.0);
        assertTrue(submitter.awaitArrived(1));
        submitter.answer("w3", 8);
        assertNotNull(c.await(r -> is(r, "w3", ExecType.NEW)));
        submitter.cancelAnswer = new OrderSubmitter.ExecResult(false, 8, OutputEvent.KIND_ORDER_CANCELED);
        sendCancel(c, "w3-x", "w3");
        Thread.sleep(300);
        assertEquals(1, submitter.cancels.get(), "the cancel reached the engine");
        acceptor.onOrderUpdate(feed(8, (byte) 0, 5, 5, CANCELED, 0, 0));
        assertNotNull(c.await(r -> is(r, "w3", ExecType.CANCELED)), "the venue's cancel was not reported");
        assertEquals(1, acceptor.endReports());
    }

    /**
     * FR-FFR-18: the ack comes back at once but its answer waits on the session's report thread, behind reports a
     * saturated store holds (acceptance run 3: from ~150K orders/s, 10.9M fills and ends lost). The order is tracked
     * at its ack, so its fill waits on it, not in the early buffer, which the venue's other fills then overrun.
     * Falsifiable: on the ff684979 acceptor the fill is evicted from the early buffer and never reported.
     */
    @Test
    void aFillWhoseAnswerWaitsBehindTheSessionsReportsIsReported() throws Exception {
        final GatedSubmitter submitter = new GatedSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "q1", Side.BUY, 5, 50.0);
        assertTrue(submitter.awaitArrived(1));
        final CountDownLatch stalled = stallReports();
        submitter.answer("q1", 7);                                               // acked; its answer waits
        acceptor.onOrderUpdate(feed(7, (byte) 0, 5, 0, FILLED, 50.0, 5));
        for (int i = 0; i < FixGatewayAcceptor.EARLY_CAPACITY + 10; i++) {
            acceptor.onOrderUpdate(feed(100_000 + i, (byte) 0, 1, 0, FILLED, 50.0, 1));   // other gateways' orders
        }
        stalled.countDown();
        assertNotNull(c.await(r -> is(r, "q1", ExecType.TRADE)), "the fill was not reported");
        assertEquals(List.of(ExecType.NEW, ExecType.TRADE),
            c.reports.stream().map(FixGatewayFillReportTest::exec).toList(), "the New, then the fill");
    }

    /** FR-FFR-18: an answer held ten times the early buffer's age still has its fill and its venue cancel reported. */
    @Test
    void updatesForAnAnswerHeldPastTheEarlyAgeAreReported() throws Exception {
        final GatedSubmitter submitter = new GatedSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        acceptor.ownFillsOnly = true;
        acceptor.earlyMaxAgeNanos = 100_000_000L;                                // 30 s, scaled to 100 ms
        sendOrder(c, "q2", Side.BUY, 5, 50.0);
        sendOrder(c, "q3", Side.BUY, 5, 50.0);
        assertTrue(submitter.awaitArrived(2));
        final CountDownLatch stalled = stallReports();
        submitter.answer("q2", 8);
        submitter.answer("q3", 9);
        acceptor.onOrderUpdate(feed(8, (byte) 0, 5, 2, PARTIAL, 50.0, 3));
        acceptor.onOrderUpdate(feed(9, (byte) 0, 5, 5, CANCELED, 0, 0));
        Thread.sleep(1_000);
        acceptor.onOrderUpdate(feed(100_000, (byte) 0, 1, 0, FILLED, 50.0, 1));   // runs the age bound
        stalled.countDown();
        assertNotNull(c.await(r -> is(r, "q2", ExecType.TRADE)), "the fill was not reported");
        assertNotNull(c.await(r -> is(r, "q3", ExecType.CANCELED)), "the venue's cancel was not reported");
        assertEquals(0, acceptor.fillsLost(), "nothing aged out: ff684979 lost both");
    }

    /**
     * FR-FFR-19, the 2026-11-11 acceptance run 4 at 300K orders/s: one gateway acked ~16.7K orders/s while the routed
     * feed stalled ~10 s (the read model's fills router a slow consumer), so ~167K orders were working here, their
     * fills not yet arrived. A resting order's fill that comes after them is still its own. Falsifiable: on 09fafeda the
     * 65,536-order LRU evicted it while working, and its fill aged out of the early buffer counted lost.
     */
    @Test
    void aRestingOrdersFillLateBehindTenSecondsOfAckedOrdersIsReported() throws Exception {
        final GatedSubmitter submitter = new GatedSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        acceptor.ownFillsOnly = true;
        sendOrder(c, "rest", Side.BUY, 5, 50.0);
        assertTrue(submitter.awaitArrived(1));
        submitter.answer("rest", 1);
        assertNotNull(c.await(r -> is(r, "rest", ExecType.NEW)), "no New");
        for (int ref = 2; ref <= 167_000; ref++) {                               // ten seconds of this gateway's acks
            track(ref);
        }
        acceptor.onOrderUpdate(feed(1, (byte) 0, 5, 0, FILLED, 50.0, 5));     // the stalled feed resumes
        assertNotNull(c.await(r -> is(r, "rest", ExecType.TRADE)), "the resting order's late fill was not reported");
        assertEquals(0, acceptor.trackedEvicted(), "a working order was evicted");
    }

    /** FR-FFR-19: past its heap bound the gateway evicts its oldest working order, and counts it. */
    @Test
    void aWorkingOrderEvictedPastTheTrackedBoundIsCounted() throws Exception {
        start(new GatedSubmitter());
        acceptor.trackedCapacity = 4;
        for (int ref = 1; ref <= 6; ref++) {
            track(ref);
        }
        assertEquals(2, acceptor.trackedEvicted());
    }

    /** Tracks {@code ref} as CLIENT1's working order whose New was sent (what {@code answered} does). */
    private void track(final long ref) throws Exception {
        final java.lang.reflect.Method m = FixGatewayAcceptor.class.getDeclaredMethod("track", long.class,
            SessionID.class, String.class, String.class, char.class);
        m.setAccessible(true);
        m.invoke(acceptor, ref, new SessionID("FIX.4.4", "TRADERX", "CLIENT1"), "o" + ref, "IBM", '1');
    }

    /** Blocks the (one) session's report thread until the latch is counted down. */
    private CountDownLatch stallReports() throws Exception {
        final java.lang.reflect.Field f = FixGatewayAcceptor.class.getDeclaredField("pipelines");
        f.setAccessible(true);
        final Object pipeline = ((Map<?, ?>) f.get(acceptor)).values().iterator().next();
        final java.lang.reflect.Field r = pipeline.getClass().getDeclaredField("reports");
        r.setAccessible(true);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch running = new CountDownLatch(1);
        ((java.util.concurrent.ExecutorService) r.get(pipeline)).execute(() -> {
            running.countDown();
            try {
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(running.await(5, java.util.concurrent.TimeUnit.SECONDS));
        return release;
    }

    /** FR-FFR-15: on the routed feed every fill is this gateway's, so one dropped from the early buffer is counted. */
    @Test
    void aFillLeftUnreportedOnTheRoutedFeedIsCounted() throws Exception {
        start(new GatedSubmitter());
        acceptor.ownFillsOnly = true;
        final int extra = 1_000;
        for (int i = 0; i < FixGatewayAcceptor.EARLY_CAPACITY + extra; i++) {
            acceptor.onOrderUpdate(feed(1 + i, (byte) 0, 1, 0, FILLED, 50.0, 1));   // no order of these is ever tracked
        }
        assertEquals(extra, acceptor.fillsLost(), "the oldest fills past the bound are lost, and counted");
    }

    private byte[] feed(final int ref, final byte side, final int qty, final int remaining, final byte status,
                        final double lastPx, final int lastQty) {
        final OrderNatsPublisher.Rec r = new OrderNatsPublisher.Rec();
        r.orderRef = ref;
        r.accountId = 11;
        r.security = "IBM";
        r.side = side;
        r.quantity = qty;
        r.remainingQty = remaining;
        r.limitPx = 50_000_000L;
        r.status = status;
        r.lastExecPx = Math.round(lastPx * 1_000_000d);
        r.lastFillQty = lastQty;
        return encoder.encode(new StringBuilder(1024), r);
    }

    @Test
    void bothSidesOfACrossReceiveFilledWithTheirQuantityAndPrice() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        final Client seller = new Client("CLIENT1");
        final Client buyer = new Client("CLIENT2");
        start(submitter, seller, buyer);

        sendOrder(seller, "s1", Side.SELL, 5, 50.0);
        assertNotNull(seller.await(r -> is(r, "s1", ExecType.NEW)), "no New for the resting sell");
        sendOrder(buyer, "b1", Side.BUY, 5, 51.0);
        assertNotNull(buyer.await(r -> is(r, "b1", ExecType.NEW)), "no New for the aggressing buy");

        // The cross, as the leader publishes it: the buy's own NEW, the resting sell (ref 1), the buy (ref 2).
        acceptor.onOrderUpdate(feed(2, (byte) 0, 5, 5, NEW, 0, 0));
        acceptor.onOrderUpdate(feed(1, (byte) 1, 5, 0, FILLED, 50.0, 5));
        acceptor.onOrderUpdate(feed(2, (byte) 0, 5, 0, FILLED, 50.0, 5));

        final ExecutionReport s = seller.await(r -> is(r, "s1", ExecType.TRADE));
        final ExecutionReport b = buyer.await(r -> is(r, "b1", ExecType.TRADE));
        assertNotNull(s, "the resting sell got no fill report");
        assertNotNull(b, "the aggressing buy got no fill report");
        assertFill(s, "ord-1", OrdStatus.FILLED, 5, 50.0, 5, 0, 50.0);
        assertFill(b, "ord-2", OrdStatus.FILLED, 5, 50.0, 5, 0, 50.0);
        assertEquals(Side.SELL, s.getChar(Side.FIELD));

        // Each fill went to its own session only, once.
        Thread.sleep(300);
        assertEquals(1, seller.count(r -> exec(r) == ExecType.TRADE), "seller fill reports");
        assertEquals(1, buyer.count(r -> exec(r) == ExecType.TRADE), "buyer fill reports");
        assertTrue(seller.reports.stream().noneMatch(r -> "b1".equals(cl(r))), "the buy's report reached the seller");
    }

    @Test
    void aPartialFillReportsPartiallyFilledThenFilledWithTheAveragePrice() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "p1", Side.BUY, 10, 52.0);
        assertNotNull(c.await(r -> is(r, "p1", ExecType.NEW)));

        final byte[] partial = feed(1, (byte) 0, 10, 6, PARTIAL, 50.0, 4);
        acceptor.onOrderUpdate(partial);
        acceptor.onOrderUpdate(partial);   // a redelivery adds nothing
        acceptor.onOrderUpdate(feed(1, (byte) 0, 10, 0, FILLED, 52.0, 6));
        acceptor.onOrderUpdate(feed(99, (byte) 0, 10, 0, FILLED, 52.0, 10));   // not this gateway's

        assertTrue(c.awaitCount(r -> exec(r) == ExecType.TRADE, 2), "want two fill reports");
        Thread.sleep(300);
        final List<ExecutionReport> fills = c.reports.stream().filter(r -> exec(r) == ExecType.TRADE).toList();
        assertEquals(2, fills.size(), "the redelivered partial or the stranger's fill was reported");
        assertEquals(0, acceptor.fillGaps(), "a complete sequence raised a gap ALERT");
        assertFill(fills.get(0), "ord-1", OrdStatus.PARTIALLY_FILLED, 4, 50.0, 4, 6, 50.0);
        assertFill(fills.get(1), "ord-1", OrdStatus.FILLED, 6, 52.0, 10, 0, 51.2);
    }

    @Test
    void aFillThisGatewayNeverReceivedIsAlertedAndCumQtyStaysTheEngines() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "g1", Side.BUY, 10, 50.0);
        assertNotNull(c.await(r -> is(r, "g1", ExecType.NEW)));

        // The partial (4 @ 50) is lost before it reaches this gateway; the final fill (6) arrives.
        acceptor.onOrderUpdate(feed(1, (byte) 0, 10, 0, FILLED, 50.0, 6));

        final ExecutionReport f = c.await(r -> is(r, "g1", ExecType.TRADE));
        assertNotNull(f, "the surviving fill was not reported");
        assertFill(f, "ord-1", OrdStatus.FILLED, 10, 50.0, 10, 0, 50.0);
        assertEquals(1, acceptor.fillGaps(), "the lost partial was not detected");
        assertEquals(1, acceptor.fillReports());
    }

    @Test
    void aFillPublishedBeforeTheAckIsReportedAfterTheNew() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        // The leader's feed beats its own egress ack to this gateway.
        submitter.beforeAck = ref -> acceptor.onOrderUpdate(feed(ref, (byte) 1, 5, 0, FILLED, 50.0, 5));
        sendOrder(c, "e1", Side.SELL, 5, 50.0);

        assertTrue(c.awaitCount(r -> "e1".equals(cl(r)), 2), "want New then the fill");
        assertEquals(ExecType.NEW, exec(c.reports.get(0)), "the fill overtook the New");
        assertFill(c.reports.get(1), "ord-1", OrdStatus.FILLED, 5, 50.0, 5, 0, 50.0);
    }

    /** FR-FIX05: a cancel ends the order, not its fills; the Canceled report keeps CumQty and AvgPx. */
    @Test
    void aCancelAfterAPartialFillReportsWhatWasFilled() throws Exception {
        final Client c = new Client("CLIENT1");
        start(new StubSubmitter(), c);
        sendOrder(c, "k1", Side.BUY, 10, 52.0);
        assertNotNull(c.await(r -> is(r, "k1", ExecType.NEW)));
        acceptor.onOrderUpdate(feed(1, (byte) 0, 10, 6, PARTIAL, 50.0, 4));
        assertNotNull(c.await(r -> is(r, "k1", ExecType.TRADE)), "no partial fill report");

        final OrderCancelRequest f = new OrderCancelRequest(new OrigClOrdID("k1"), new ClOrdID("k1x"),
            new Side(Side.BUY), new TransactTime());
        f.set(new Symbol("IBM"));
        Session.sendToTarget(f, c.sessionId);

        final ExecutionReport x = c.await(r -> is(r, "k1x", ExecType.CANCELED));
        assertNotNull(x, "no Canceled report");
        assertEquals(OrdStatus.CANCELED, x.getChar(OrdStatus.FIELD));
        assertEquals(4.0, x.getDouble(CumQty.FIELD), "a canceled order keeps its fill");
        assertEquals(0.0, x.getDouble(LeavesQty.FIELD));
        assertEquals(50.0, x.getDouble(AvgPx.FIELD), 1e-9);
    }

    /** FR-DRS-27: OrderStatus for a CANCELED order reports the read model's filled quantity, never qty - 0. */
    @Test
    void orderStatusOfACanceledOrderReportsItsFillNotItsQuantity() throws Exception {
        final Client c = new Client("CLIENT1");
        final List<OrderStatusSource.OrderView> rows = new CopyOnWriteArrayList<>(List.of(
            new OrderStatusSource.OrderView(1, "Buy", 10, 0, "CANCELED", "IBM", 4),
            new OrderStatusSource.OrderView(2, "Sell", 3, 0, "CANCELED", "IBM"),            // read model without the field
            new OrderStatusSource.OrderView(3, "Buy", 10, 6, "PARTIALLY_FILLED", "IBM")));   // ditto, still open
        start(new StubSubmitter(), (account, all) -> rows, c);
        for (int ref = 1; ref <= 3; ref++) {
            final OrderStatusRequest h = new OrderStatusRequest();
            h.set(new ClOrdID("h" + ref));
            h.set(new OrderID("ord-" + ref));
            h.set(new Side(Side.BUY));
            h.set(new Symbol("IBM"));
            h.set(new Account("11"));
            Session.sendToTarget(h, c.sessionId);
        }
        final double[][] want = {{4, 0}, {0, 0}, {4, 6}};   // CumQty, LeavesQty
        for (int ref = 1; ref <= 3; ref++) {
            final String id = "h" + ref;
            final ExecutionReport r = c.await(m -> is(m, id, ExecType.ORDER_STATUS));
            assertNotNull(r, "no status report for ord-" + ref);
            assertEquals(want[ref - 1][0], r.getDouble(CumQty.FIELD), "CumQty of ord-" + ref);
            assertEquals(want[ref - 1][1], r.getDouble(LeavesQty.FIELD), "LeavesQty of ord-" + ref);
        }
    }

    /** ADR-093: the fill feed is a strict consumer of /orders; a booking event carrying clientOrderKey reads as before. */
    @Test
    void aBookingEventCarryingTheClientKeyIsReadAsBefore() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "k1", Side.BUY, 5, 51.0);
        assertNotNull(c.await(r -> is(r, "k1", ExecType.NEW)), "no New");
        final String booking = new String(feed(1, (byte) 0, 5, 5, NEW, 0, 0), java.nio.charset.StandardCharsets.UTF_8)
            .replace("\"status\"", "\"clientOrderKey\":\"18446744073709551615\",\"status\"");
        assertTrue(booking.contains("clientOrderKey"), booking);
        acceptor.onOrderUpdate(booking.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        acceptor.onOrderUpdate(feed(1, (byte) 0, 5, 0, FILLED, 50.0, 5));
        final ExecutionReport r = c.await(m -> is(m, "k1", ExecType.TRADE));
        assertNotNull(r, "no fill report after a keyed booking event");
        assertFill(r, "ord-1", OrdStatus.FILLED, 5, 50.0, 5, 0, 50.0);
    }

    /**
     * ADR-093: an OrderStatusRequest for a ClOrdID this gateway holds no ref for (another replica booked it, or its ref
     * was lost past ADR-090's answer ring) is found by the key the gateway submits it under. Fails on a gateway that
     * answers "unknown order".
     */
    @Test
    void orderStatusOfAClOrdIdThisGatewayNeverSawIsFoundByItsClientKey() throws Exception {
        final Client c = new Client("CLIENT1");
        final long key = ClusterGatewayMain.clientOrderKey("CLIENT1\u0001x9");
        start(new StubSubmitter(), new OrderStatusSource() {
            @Override
            public List<OrderView> orders(final int account, final boolean all) {
                return List.of(new OrderView(7, "Buy", 10, 10, "NEW", "IBM", 0));   // not this order
            }

            @Override
            public List<OrderView> ordersByKey(final int account, final long clientKey) {
                return account == 11 && clientKey == key ? List.of(new OrderView(42, "Buy", 5, 0, "FILLED", "IBM", 5))
                    : List.of();
            }
        }, c);
        final OrderStatusRequest h = new OrderStatusRequest();
        h.set(new ClOrdID("x9"));
        h.set(new Side(Side.BUY));
        h.set(new Symbol("IBM"));
        h.set(new Account("11"));
        Session.sendToTarget(h, c.sessionId);
        final ExecutionReport r = c.await(m -> is(m, "x9", ExecType.ORDER_STATUS));
        assertNotNull(r, "no status report found by the client key");
        assertEquals("ord-42", r.getString(OrderID.FIELD));
        assertEquals(OrdStatus.FILLED, r.getChar(OrdStatus.FIELD));
    }

    /** FR-XOC-03: a resent ClOrdID whose original left retention is restated as that order, never rejected. */
    @Test
    void aResentClOrdIdWhoseOriginalLeftRetentionIsRestatedNotRejected() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        submitter.answer = new OrderSubmitter.ExecResult(false, 42, OutputEvent.KIND_ORDER_REJECTED,
            (byte) finos.traderx.ordermatcher.risk.RiskReason.DUPLICATE.ordinal());
        final Client c = new Client("CLIENT1");
        start(submitter, (account, all) -> List.of(new OrderStatusSource.OrderView(42, "Buy", 5, 0, "FILLED", "IBM", 5)), c);
        sendOrder(c, "r1", Side.BUY, 5, 50.0);
        final ExecutionReport r = c.await(m -> "r1".equals(cl(m)));
        assertNotNull(r, "the resend was not answered");
        assertEquals(ExecType.ORDER_STATUS, r.getChar(ExecType.FIELD));
        assertEquals("ord-42", r.getString(OrderID.FIELD), "the original order, not NONE");
        assertEquals(OrdStatus.FILLED, r.getChar(OrdStatus.FIELD), "its state from the read model");
        assertEquals(5.0, r.getDouble(CumQty.FIELD));
        assertTrue(r.getString(Text.FIELD).startsWith("DUPLICATE"));
    }

    @Test
    void withoutAReadModelTheRestatementSaysOnlyThatTheOrderIsDone() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        submitter.answer = new OrderSubmitter.ExecResult(false, 42, OutputEvent.KIND_ORDER_REJECTED,
            (byte) finos.traderx.ordermatcher.risk.RiskReason.DUPLICATE.ordinal());
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "r2", Side.BUY, 5, 50.0);
        final ExecutionReport r = c.await(m -> "r2".equals(cl(m)));
        assertNotNull(r, "the resend was not answered");
        assertEquals(ExecType.ORDER_STATUS, r.getChar(ExecType.FIELD));
        assertEquals("ord-42", r.getString(OrderID.FIELD));
        assertEquals(OrdStatus.DONE_FOR_DAY, r.getChar(OrdStatus.FIELD), "never OrdStatus=8");
    }

    /**
     * FR-XOC-03 (suspect 17, step 4): the DUPLICATE answer keeps ClOrdID -> ord-42, and a cancel by that OrigClOrdID
     * the engine answers ORDER_NOT_FOUND (the original left retention) names ord-42 as done, never OrderID NONE. A
     * ClOrdID this session never mapped is still "no such order".
     */
    @Test
    void aCancelOfARestatedOrderTheEngineNoLongerRetainsNamesItAsDone() throws Exception {
        final StubSubmitter submitter = new StubSubmitter();
        submitter.answer = new OrderSubmitter.ExecResult(false, 42, OutputEvent.KIND_ORDER_REJECTED,
            (byte) finos.traderx.ordermatcher.risk.RiskReason.DUPLICATE.ordinal());
        submitter.cancelAnswer = new OrderSubmitter.ExecResult(false, 42, OutputEvent.KIND_ORDER_NOT_FOUND);
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "r3", Side.BUY, 5, 50.0);
        assertNotNull(c.await(m -> "r3".equals(cl(m))), "the resend was not answered");
        sendCancel(c, "c3", "r3");
        sendCancel(c, "c4", "never-sent");
        final quickfix.fix44.OrderCancelReject r = c.awaitReject("c3");
        assertNotNull(r, "no cancel reject");
        assertEquals("ord-42", r.getString(OrderID.FIELD), "the mapped order, not NONE");
        assertEquals(OrdStatus.DONE_FOR_DAY, r.getChar(OrdStatus.FIELD));
        assertEquals(quickfix.field.CxlRejReason.TOO_LATE_TO_CANCEL, r.getInt(quickfix.field.CxlRejReason.FIELD));
        final quickfix.fix44.OrderCancelReject u = c.awaitReject("c4");
        assertNotNull(u, "no cancel reject for the unmapped ClOrdID");
        assertEquals("NONE", u.getString(OrderID.FIELD), "an order this session never had stays unknown");
    }

    private static void sendCancel(final Client c, final String clOrdId, final String origClOrdId) throws Exception {
        final quickfix.fix44.OrderCancelRequest cancel = new quickfix.fix44.OrderCancelRequest(
            new quickfix.field.OrigClOrdID(origClOrdId), new ClOrdID(clOrdId), new Side(Side.BUY), new TransactTime());
        cancel.set(new Symbol("IBM"));
        cancel.set(new Account("11"));
        Session.sendToTarget(cancel, c.sessionId);
    }

    /** The engine as seen across an election: each answer in turn; a null answer may come with a leader change. */
    private static final class FailoverSubmitter implements OrderSubmitter {
        final List<ExecResult> answers = new CopyOnWriteArrayList<>();
        final List<String> keys = new CopyOnWriteArrayList<>();
        final AtomicInteger changes = new AtomicInteger();
        volatile boolean electionOnNull = true;

        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                      final char side, final int qty, final long limitPxTicks) {
            keys.add(clOrdId);
            final ExecResult r = answers.isEmpty() ? null : answers.remove(0);
            if (r == null && electionOnNull) {
                changes.incrementAndGet();   // the gateway reaped this order at a new leader
            }
            return r;
        }

        @Override
        public long leaderChanges() {
            return changes.get();
        }
    }

    /** FR-XOC-06: an order stranded by an election and lost with the old leader is booked once, by its resubmission. */
    @Test
    void anOrderStrandedByAnElectionIsResubmittedUnderItsKeyAndAnswered() throws Exception {
        final FailoverSubmitter submitter = new FailoverSubmitter();
        submitter.answers.add(null);
        submitter.answers.add(new OrderSubmitter.ExecResult(true, 7, OutputEvent.KIND_ORDER_ACCEPTED));
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "f1", Side.BUY, 5, 50.0);
        final ExecutionReport r = c.await(m -> is(m, "f1", ExecType.NEW));
        assertNotNull(r, "the stranded order was never answered");
        assertEquals("ord-7", r.getString(OrderID.FIELD));
        assertEquals(2, submitter.keys.size(), "want the original and one resubmission");
        assertEquals(submitter.keys.get(0), submitter.keys.get(1), "the resubmission must carry the same key");
    }

    /** FR-XOC-06: one that committed before the election is restated by the engine's key, never booked twice. */
    @Test
    void anOrderThatCommittedBeforeTheElectionIsRestatedNotBookedTwice() throws Exception {
        final FailoverSubmitter submitter = new FailoverSubmitter();
        submitter.answers.add(null);
        submitter.answers.add(new OrderSubmitter.ExecResult(false, 42, OutputEvent.KIND_ORDER_REJECTED,
            (byte) finos.traderx.ordermatcher.risk.RiskReason.DUPLICATE.ordinal()));
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "f2", Side.BUY, 5, 50.0);
        final ExecutionReport r = c.await(m -> "f2".equals(cl(m)));
        assertNotNull(r, "the stranded order was never answered");
        assertEquals(ExecType.ORDER_STATUS, r.getChar(ExecType.FIELD));
        assertEquals("ord-42", r.getString(OrderID.FIELD));
    }

    /** A timeout with no election is not resubmitted: past the key window a resubmission could execute twice. */
    @Test
    void anAmbiguousAnswerWithoutAnElectionIsNotResubmitted() throws Exception {
        final FailoverSubmitter submitter = new FailoverSubmitter();
        submitter.electionOnNull = false;
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "f3", Side.BUY, 5, 50.0);
        Thread.sleep(1_000);
        assertEquals(1, submitter.keys.size(), "resubmitted without a leader change");
        assertTrue(c.reports.stream().noneMatch(m -> "f3".equals(cl(m))), "an ambiguous order was answered");
    }

    /** Elections back to back: bounded, so a flapping cluster cannot hold the session's dispatcher forever. */
    @Test
    void resubmissionsAcrossRepeatedElectionsAreBounded() throws Exception {
        final FailoverSubmitter submitter = new FailoverSubmitter();
        final Client c = new Client("CLIENT1");
        start(submitter, c);
        sendOrder(c, "f4", Side.BUY, 5, 50.0);
        Thread.sleep(1_000);
        assertEquals(3, submitter.keys.size(), "want the original and two resubmissions");
    }

    // ----- harness ---------------------------------------------------------------------------

    private static void assertFill(final ExecutionReport r, final String orderId, final char ordStatus,
                                   final int lastQty, final double lastPx, final int cum, final int leaves,
                                   final double avgPx) throws FieldNotFound {
        assertEquals(orderId, r.getString(OrderID.FIELD));
        assertEquals(ExecType.TRADE, r.getChar(ExecType.FIELD));
        assertEquals(ordStatus, r.getChar(OrdStatus.FIELD));
        assertEquals(lastQty, r.getDouble(LastQty.FIELD));
        assertEquals(lastPx, r.getDouble(LastPx.FIELD), 1e-9);
        assertEquals(cum, r.getDouble(CumQty.FIELD));
        assertEquals(leaves, r.getDouble(LeavesQty.FIELD));
        assertEquals(avgPx, r.getDouble(AvgPx.FIELD), 1e-9);
    }

    private void start(final OrderSubmitter submitter, final Client... clients) throws Exception {
        start(submitter, null, clients);
    }

    private void start(final OrderSubmitter submitter, final OrderStatusSource status, final Client... clients)
        throws Exception {
        final int port = freePort();
        acceptor = new FixGatewayAcceptor(submitter, status, port, "TRADERX", 11, List.of("CLIENT1", "CLIENT2"));
        acceptor.start();
        for (final Client c : clients) {
            final SessionSettings settings = new SessionSettings();
            final Properties d = new Properties();
            d.setProperty("ConnectionType", "initiator");
            d.setProperty("SocketConnectHost", "127.0.0.1");
            d.setProperty("SocketConnectPort", String.valueOf(port));
            d.setProperty("StartTime", "00:00:00");
            d.setProperty("EndTime", "00:00:00");
            d.setProperty("HeartBtInt", "5");
            d.setProperty("ReconnectInterval", "1");
            d.setProperty("PersistMessages", "N");
            settings.set(d);
            final SessionID sid = new SessionID("FIX.4.4", c.compId, "TRADERX");
            settings.setString(sid, "BeginString", "FIX.4.4");
            final SocketInitiator init = new SocketInitiator(c, new MemoryStoreFactory(), settings,
                new ScreenLogFactory(false, false, false), new DefaultMessageFactory());
            init.start();
            initiators.add(init);
            assertTrue(c.loggedOn.await(20, TimeUnit.SECONDS), c.compId + " did not log on");
        }
    }

    private static void sendOrder(final Client c, final String clOrdId, final char side, final int qty,
                                  final double px) throws Exception {
        final NewOrderSingle order = new NewOrderSingle(new ClOrdID(clOrdId), new Side(side),
            new TransactTime(), new OrdType(OrdType.LIMIT));
        order.set(new Symbol("IBM"));
        order.set(new OrderQty(qty));
        order.set(new Price(px));
        order.set(new Account("11"));
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        Session.sendToTarget(order, c.sessionId);
    }

    private static boolean is(final ExecutionReport r, final String clOrdId, final char execType) {
        return clOrdId.equals(cl(r)) && exec(r) == execType;
    }

    private static char exec(final ExecutionReport r) {
        try {
            return r.getChar(ExecType.FIELD);
        } catch (final FieldNotFound e) {
            return 0;
        }
    }

    private static String cl(final ExecutionReport r) {
        try {
            return r.isSetField(ClOrdID.FIELD) ? r.getString(ClOrdID.FIELD) : null;
        } catch (final FieldNotFound e) {
            return null;
        }
    }

    private static final class Client implements Application {
        final String compId;
        final CountDownLatch loggedOn = new CountDownLatch(1);
        final List<ExecutionReport> reports = new CopyOnWriteArrayList<>();
        final List<quickfix.fix44.OrderCancelReject> rejects = new CopyOnWriteArrayList<>();
        volatile SessionID sessionId;

        Client(final String compId) {
            this.compId = compId;
        }

        ExecutionReport await(final Predicate<ExecutionReport> p) throws InterruptedException {
            for (int i = 0; i < 100; i++) {
                for (final ExecutionReport r : reports) {
                    if (p.test(r)) {
                        return r;
                    }
                }
                Thread.sleep(50);
            }
            return null;
        }

        quickfix.fix44.OrderCancelReject awaitReject(final String clOrdId) throws Exception {
            for (int i = 0; i < 100; i++) {
                for (final quickfix.fix44.OrderCancelReject r : rejects) {
                    if (clOrdId.equals(r.getString(ClOrdID.FIELD))) {
                        return r;
                    }
                }
                Thread.sleep(50);
            }
            return null;
        }

        boolean awaitCount(final Predicate<ExecutionReport> p, final int n) throws InterruptedException {
            for (int i = 0; i < 100 && count(p) < n; i++) {
                Thread.sleep(50);
            }
            return count(p) >= n;
        }

        long count(final Predicate<ExecutionReport> p) {
            return reports.stream().filter(p).count();
        }

        @Override
        public void onCreate(final SessionID sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onLogon(final SessionID sessionId) {
            this.sessionId = sessionId;
            loggedOn.countDown();
        }

        @Override
        public void onLogout(final SessionID sessionId) { }

        @Override
        public void toAdmin(final Message message, final SessionID sessionId) { }

        @Override
        public void fromAdmin(final Message message, final SessionID sessionId) { }

        @Override
        public void toApp(final Message message, final SessionID sessionId) { }

        @Override
        public void fromApp(final Message message, final SessionID sessionId) {
            if (message instanceof ExecutionReport report) {
                reports.add(report);
            } else if (message instanceof quickfix.fix44.OrderCancelReject reject) {
                rejects.add(reject);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
