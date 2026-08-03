package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE CROSS-SERVICE SEAM: a control-plane limit change reaches the in-memory risk limits and
 * actually rejects an order.
 *
 * <p><b>The gap this closes.</b> Both halves were already tested, and neither half proves the join:
 * <ul>
 *   <li>{@code RiskControlControllerTest} drives {@code POST /risk/control/*} against a <b>mocked</b>
 *       {@code LmaxEngine} and only verifies {@code submitPolicyControl(...)} was called with the
 *       right version. It cannot know whether the engine does anything with it.</li>
 *   <li>{@code BlpRiskStateTest} sets limits by calling {@code putLimits(...)} <b>directly</b> and
 *       asserts the rejection precedence. It never sees a control event.</li>
 * </ul>
 * So the wire between them — control event → {@code MatchingEngine.onPolicyControl} →
 * {@code BlpRiskState.putLimits} → {@code decideAndReserve} → reject — was covered nowhere. This is
 * exactly the shape of bug this project has shipped before: a risk decision that changed while the
 * caller still saw success.
 *
 * <p><b>Why it is falsifiable rather than decorative.</b> The same order is submitted twice, and the
 * ONLY thing that changes between them is the control event. The first must trade; the second must
 * not. A build where the control event never reached the risk state would trade twice and fail here.
 * The assertion is made at the effect end ({@code tradeCounter}), not on the acknowledgement — a
 * 200/accept has repeatedly meant nothing was booked on this project.
 *
 * <p>Pure in-process: no cluster, no broker, no Docker. Runs in the unit tier on every push.
 */
class ControlPlaneLimitRejectSeamTest {

    private static final String TICKER = "AAPL";
    private static final int SELLER = 22214;
    private static final int BUYER = 42422;
    private static final long PX = 100_000_000L;      // 100.000 in ticks
    private static final int QTY = 10;
    private static final long ROOMY_CONCENTRATION = 1_000_000_000_000_000L;

    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
    private MatchingEngineClusteredService service;
    private long timestamp;
    private int security;

    @BeforeEach
    void setUp() {
        service = new MatchingEngineClusteredService();
        // initEngine(), not onStart(): onStart dereferences the Cluster for its idle strategy.
        service.initEngine();
        apply(accountControl(SELLER));
        apply(accountControl(BUYER));
        security = registerSymbol(TICKER);
        apply(securityControl(security));
        apply(priceTick(security, PX));
    }

    @Test
    void tighteningThePolicyLimitViaAControlEventRejectsAnOrderThatPreviouslyTraded() {
        // 1. A roomy limit, published the way the control plane publishes it.
        apply(policyControl(false, 1L, 50_000, ROOMY_CONCENTRATION));

        final long before = service.engine().tradeCounter();
        cross();
        final long afterRoomy = service.engine().tradeCounter();
        assertTrue(afterRoomy > before,
            "precondition: with a roomy limit the crossing pair must actually trade");

        // 2. The ONLY change: a control event tightening the position limit below the order size.
        apply(policyControl(false, 2L, QTY - 1, ROOMY_CONCENTRATION));

        // 3. The identical order must now be rejected, observed at the EFFECT END.
        cross();
        assertEquals(afterRoomy, service.engine().tradeCounter(),
            "after the limit was tightened the same order must NOT book a trade");
    }

    @Test
    void theKillSwitchArrivesByTheSamePathAndStopsTrading() {
        apply(policyControl(false, 1L, 50_000, ROOMY_CONCENTRATION));
        cross();
        final long traded = service.engine().tradeCounter();
        assertTrue(traded > 0L, "precondition: trading works before the kill switch");

        apply(policyControl(true, 2L, 50_000, ROOMY_CONCENTRATION));
        cross();
        assertEquals(traded, service.engine().tradeCounter(),
            "kill switch set through the control path must stop new trades");
    }

    /** A resting sell and a marketable buy at the same price — one trade when risk allows it. */
    private void cross() {
        apply(order(SELLER, InputEvent.SIDE_SELL));
        apply(order(BUYER, InputEvent.SIDE_BUY));
    }

    private int registerSymbol(final String ticker) {
        codec.encodeSymbolRegister(buffer, 0, ++timestamp, ticker);
        service.onSessionMessage(null, timestamp, buffer, 0, AeronReplicationCodec.SYMBOL_BYTES, null);
        final int id = service.symbolIdFor(ticker);
        assertTrue(id >= 0, "registration must assign an id for " + ticker);
        return id;
    }

    private void apply(final InputEvent event) {
        codec.encodeInput(buffer, 0, event, 0, 0, 0);
        service.onSessionMessage(null, ++timestamp, buffer, 0, AeronReplicationCodec.INPUT_BYTES, null);
    }

    private InputEvent order(final int accountId, final byte side) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.accountId = accountId;
        e.securityId = security;
        e.side = side;
        e.qty = QTY;
        e.limitPx = PX;
        e.eventTimeMillis = timestamp;
        return e;
    }

    private InputEvent priceTick(final int securityId, final long px) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_PRICE_TICK;
        e.securityId = securityId;
        e.priceTicks = px;
        e.eventTimeMillis = timestamp;
        return e;
    }

    private InputEvent accountControl(final int accountId) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_ACCOUNT_CONTROL;
        e.accountId = accountId;
        e.setControlEnabled(true);
        e.setControlVersion(1);
        e.eventTimeMillis = timestamp;
        return e;
    }

    private InputEvent securityControl(final int securityId) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_SECURITY_CONTROL;
        e.securityId = securityId;
        e.setControlEnabled(true);
        e.setControlVersion(1);
        e.eventTimeMillis = timestamp;
        return e;
    }

    /** Shaped exactly as RiskControlController's policy POST publishes it (ADR-020 payload slots). */
    private InputEvent policyControl(final boolean killSwitch, final long version,
                                     final int maxPositionQty, final long maxConcentrationTicks) {
        final InputEvent e = new InputEvent();
        e.type = InputEvent.TYPE_POLICY_CONTROL;
        e.setControlEnabled(killSwitch);
        e.setControlVersion(version);
        // ADR-020 payload slots: the policy limits ride qty/limitPx (see InputEvent javadoc and
        // LmaxEngine.submitPolicyControl, which encodes them exactly this way).
        e.qty = maxPositionQty;
        e.limitPx = maxConcentrationTicks;
        e.eventTimeMillis = timestamp;
        return e;
    }
}
