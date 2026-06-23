package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import finos.traderx.ordermatcher.risk.RiskReason;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RecoveryAndFollowerInvariantTest {
    @Test
    void rejectedCommandHasDecisionButNoExecutableReservationTradeOrPositionOutput() throws Exception {
        BlpRiskState risk = risk(99L);
        OutputPublisher publisher = new OutputPublisher(null);
        List<Byte> kinds = new ArrayList<>();
        publisher.attachRecoverySink((event, sequence, endOfBatch) -> kinds.add(event.kind));
        MatchingEngine matcher = matcher(risk, publisher);

        matcher.onEvent(order(0L, 1, 500L, 10, 10L), 0L, true);

        MatchingEngine.Image image = matcher.captureImage();
        assertEquals(1, image.orderRefs().length, "rejection remains an auditable decision record");
        assertEquals(RiskReason.CREDIT_LIMIT.ordinal(), image.riskReasons()[0]);
        assertEquals(0, image.remainingQuantities()[0]);
        assertEquals(0L, risk.reservedNotional(7));
        assertEquals(List.of(OutputEvent.KIND_ORDER_REJECTED), kinds);
        assertFalse(kinds.contains(OutputEvent.KIND_TRADE_BOOKED));
        assertFalse(kinds.contains(OutputEvent.KIND_POSITION_UPDATED));
    }

    @Test
    void warmFollowerAppliesIdenticalGlobalInputAndIsPromotableAtSameState() throws Exception {
        BlpRiskState activeRisk = risk(10_000L);
        MatchingEngine active = matcher(activeRisk, new OutputPublisher(null));
        BlpRiskState followerRisk = risk(10_000L);
        MatchingEngine follower = matcher(followerRisk, new OutputPublisher(null));
        ReplicatorStub replicator = new ReplicatorStub(follower);

        InputEvent create = order(0L, 1, 51L, 5, 10L);
        active.onEvent(create, 0L, true);
        replicator.onEvent(create, 0L, true);
        InputEvent cancel = new InputEvent();
        cancel.seq = 1L;
        cancel.type = InputEvent.TYPE_ORDER_CANCEL;
        cancel.orderRef = 1;
        active.onEvent(cancel, 1L, true);
        replicator.onEvent(cancel, 1L, true);

        MatchingEngine.Image activeImage = active.captureImage();
        MatchingEngine.Image followerImage = replicator.followerImage();
        assertNotNull(followerImage);
        assertEquals(1L, replicator.replicatedSeq());
        assertArrayEquals(activeImage.orderRefs(), followerImage.orderRefs());
        assertArrayEquals(activeImage.statuses(), followerImage.statuses());
        assertArrayEquals(activeImage.remainingQuantities(), followerImage.remainingQuantities());
        assertEquals(activeRisk.reservedNotional(7), followerRisk.reservedNotional(7));
    }

    private static BlpRiskState risk(long creditLimit) {
        BlpRiskState risk = new BlpRiskState(8, 8, 32, 32, creditLimit,
            1_000, Long.MAX_VALUE, Long.MAX_VALUE, new RiskMetrics());
        risk.putLimits(1_000, Long.MAX_VALUE);
        risk.putAccount(7, true);
        risk.putSecurity(1, true);
        risk.onPrice(1, 10L, 1L);
        return risk;
    }

    private static MatchingEngine matcher(BlpRiskState risk, OutputPublisher publisher) {
        return new MatchingEngine(publisher, new HotPathMetrics(), 8, 1_000, 32, 32, risk, 60_000L);
    }

    private static InputEvent order(long seq, int orderRef, long clientKey, int quantity, long price) {
        InputEvent event = new InputEvent();
        event.seq = seq;
        event.type = InputEvent.TYPE_ORDER_NEW;
        event.orderRef = orderRef;
        event.accountId = 7;
        event.securityId = 1;
        event.side = InputEvent.SIDE_BUY;
        event.qty = quantity;
        event.limitPx = price;
        event.clientOrderKey = clientKey;
        event.eventTimeMillis = 1L;
        return event;
    }
}
