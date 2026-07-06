package finos.traderx.ordermatcher.lmax;

import com.lmax.disruptor.RingBuffer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Bounded terminal-order retention (blp.orders.max-retained): without it every FILLED/CANCELED
 * order is retained forever in the BLP and the read model, and old-gen GC pressure collapses
 * sustained throughput. Open orders must never be evicted; evicted (ancient, terminal) refs
 * answer not-found.
 */
class OrderRetentionEvictionTest {

    @Test
    void terminalOrdersAreEvictedFifoAndBoundedByCap() {
        RingBuffer<OutputEvent> ring = RingBuffer.createSingleProducer(OutputEvent::newInstance, 1024);
        MatchingEngine blp = new MatchingEngine(new OutputPublisher(ring), new HotPathMetrics(),
            16, 1000, 16, 1024, 8);

        long seq = 0;
        for (int ref = 1; ref <= 20; ref++) {
            blp.onEvent(newOrder(ref, ++seq), seq, true);   // rests open: no price tick seen
            blp.onEvent(cancel(ref, ++seq), seq, true);      // -> terminal, enters the FIFO
        }

        assertEquals(8, blp.terminalOrdersRetained());
        assertEquals(12, blp.ordersEvicted());
        assertEquals(8, blp.allOrderTuples().size());

        // The oldest terminal ref was evicted: a second cancel now answers not-found...
        blp.onEvent(cancel(1, ++seq), seq, true);
        assertEquals(OutputEvent.KIND_ORDER_NOT_FOUND, ring.get(ring.getCursor()).kind);

        // ...while a retained terminal ref is still addressable (009 parity: re-published unchanged).
        blp.onEvent(cancel(20, ++seq), seq, true);
        assertEquals(OutputEvent.KIND_ORDER_CANCELED, ring.get(ring.getCursor()).kind);
    }

    @Test
    void openOrdersAreNeverEvicted() {
        RingBuffer<OutputEvent> ring = RingBuffer.createSingleProducer(OutputEvent::newInstance, 1024);
        MatchingEngine blp = new MatchingEngine(new OutputPublisher(ring), new HotPathMetrics(),
            16, 1000, 16, 1024, 4);

        long seq = 0;
        for (int ref = 1; ref <= 3; ref++) {
            blp.onEvent(newOrder(ref, ++seq), seq, true);    // stay open throughout
        }
        for (int ref = 4; ref <= 13; ref++) {
            blp.onEvent(newOrder(ref, ++seq), seq, true);
            blp.onEvent(cancel(ref, ++seq), seq, true);       // churn terminal refs past the cap
        }

        assertEquals(4, blp.terminalOrdersRetained());
        // The open orders survived the churn and cancel cleanly (not not-found).
        for (int ref = 1; ref <= 3; ref++) {
            blp.onEvent(cancel(ref, ++seq), seq, true);
            assertEquals(OutputEvent.KIND_ORDER_CANCELED, ring.get(ring.getCursor()).kind);
        }
    }

    @Test
    void readModelEvictsTerminalSnapshotsFifoAndKeepsOpenOnes() {
        SymbolTable symbols = new SymbolTable(16);
        symbols.idFor("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel(4);

        readModel.apply(orderEvent(symbols, 100, RestingOrder.STATUS_NEW), symbols);
        for (int ref = 1; ref <= 10; ref++) {
            readModel.apply(orderEvent(symbols, ref, RestingOrder.STATUS_CANCELED), symbols);
        }

        assertEquals(6, readModel.evictedOrders());
        assertEquals(5, readModel.totalOrders());            // 4 terminal + 1 open
        assertNull(readModel.get(1));                        // oldest terminal: evicted
        assertNotNull(readModel.get(10));                    // newest terminal: retained
        assertNotNull(readModel.get(100));                   // open: never evicted
    }

    @Test
    void republishOfAlreadyTerminalOrderDoesNotDoubleEnterTheFifo() {
        SymbolTable symbols = new SymbolTable(16);
        symbols.idFor("IBM");
        InMemoryOrderReadModel readModel = new InMemoryOrderReadModel(2);

        // Ref 1 goes terminal, then is re-published terminal (009 parity: cancel-of-canceled
        // returns it unchanged). The re-publish must not occupy a second FIFO slot.
        readModel.apply(orderEvent(symbols, 1, RestingOrder.STATUS_CANCELED), symbols);
        readModel.apply(orderEvent(symbols, 1, RestingOrder.STATUS_CANCELED), symbols);
        readModel.apply(orderEvent(symbols, 2, RestingOrder.STATUS_CANCELED), symbols);

        assertEquals(0, readModel.evictedOrders());          // 2 distinct terminals fit the cap of 2
        readModel.apply(orderEvent(symbols, 3, RestingOrder.STATUS_CANCELED), symbols);
        assertEquals(1, readModel.evictedOrders());          // third distinct terminal evicts ref 1
        assertNull(readModel.get(1));
        assertNotNull(readModel.get(2));
        assertNotNull(readModel.get(3));
    }

    private static InputEvent newOrder(int orderRef, long seq) {
        InputEvent e = new InputEvent();
        e.seq = seq;
        e.type = InputEvent.TYPE_ORDER_NEW;
        e.orderRef = orderRef;
        e.accountId = 22214;
        e.securityId = 0;
        e.side = InputEvent.SIDE_BUY;
        e.qty = 100;
        e.limitPx = Px.toTicks(new BigDecimal("102.000"));
        e.ingressNanos = System.nanoTime();
        e.eventTimeMillis = 1_700_000_000_000L + seq;
        return e;
    }

    private static InputEvent cancel(int orderRef, long seq) {
        InputEvent e = new InputEvent();
        e.seq = seq;
        e.type = InputEvent.TYPE_ORDER_CANCEL;
        e.orderRef = orderRef;
        e.ingressNanos = System.nanoTime();
        e.eventTimeMillis = 1_700_000_000_000L + seq;
        return e;
    }

    private static OutputEvent orderEvent(SymbolTable symbols, int orderRef, byte status) {
        OutputEvent e = new OutputEvent();
        e.kind = status == RestingOrder.STATUS_CANCELED
            ? OutputEvent.KIND_ORDER_CANCELED : OutputEvent.KIND_ORDER_ACCEPTED;
        e.flags = status == RestingOrder.STATUS_CANCELED ? OutputEvent.FLAG_CANCEL : OutputEvent.FLAG_CREATE;
        e.orderRef = orderRef;
        e.accountId = 22214;
        e.securityId = symbols.idFor("IBM");
        e.side = (byte) finos.traderx.ordermatcher.model.OrderSide.Buy.ordinal();
        e.quantity = 100;
        e.remainingQty = status == RestingOrder.STATUS_CANCELED ? 0 : 100;
        e.limitPx = Px.toTicks(new BigDecimal("102.000"));
        e.status = status;
        e.lastExecPx = Px.NONE;
        e.createdAtMillis = 1_700_000_000_000L;
        e.updatedAtMillis = 1_700_000_000_000L;
        e.ingressNanos = System.nanoTime();
        return e;
    }
}
