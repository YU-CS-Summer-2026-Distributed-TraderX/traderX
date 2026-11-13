package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.PushSubscribeOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-DRS-57 (ADR-106): a stream that stays frozen or slow for longer than a lane's ring costs no record. Each case
 * holds NATS frozen while the member applies several rings' worth at a paced rate, so the ring is overwritten many
 * times over before the stream can store anything. Before this rule the overwritten records were lost (run 5,
 * 2026-11-11: history NEW 116,662 short). The stream must read back every record once, in apply order: the exact
 * list, not a count.
 *
 * <p>This file uses only what the bridges had before FR-DRS-57, so it compiles and FAILS on the parent build.
 */
@Testcontainers(disabledWithoutDocker = true)
class LaneSpoolIT {
    /** Production's smallest ring, and 3.8 rings of each record applied during the freeze. */
    static final int RING = 131_072, STORED = 100, MORE = 500_000;

    /** NATS thaws: what the frozen socket took is stored late, the rest was never sent. */
    @Test
    void aStreamFrozenForLongerThanTheRingLosesNothing() throws Exception {
        try (RestartableNats js = new RestartableNats()) {
            js.addFeedStream();
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e9", null, RING, 1);
            final OrderNatsPublisher orders = new OrderNatsPublisher(js.url, "/orders", "e9", null, RING, 1);
            trades.start();
            orders.start();
            apply(1, STORED, trades, orders);
            awaitStored(js.url, STORED, STORED);
            js.pause();
            apply(STORED + 1, STORED + MORE, trades, orders);
            js.unpause();
            assertEachOnceInOrder(js.url, trades, orders);
            trades.stop();
            orders.stop();
        }
    }

    /** NATS dies frozen: nothing sent during the freeze is stored, so the lane republishes all of it from its spool. */
    @Test
    void aNatsRestartAfterMoreThanARingOfUnstoredRecordsLosesNothing() throws Exception {
        try (RestartableNats js = new RestartableNats()) {
            js.addFeedStream();
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e9", null, RING, 1);
            final OrderNatsPublisher orders = new OrderNatsPublisher(js.url, "/orders", "e9", null, RING, 1);
            trades.start();
            orders.start();
            apply(1, STORED, trades, orders);
            awaitStored(js.url, STORED, STORED);
            js.pause();
            apply(STORED + 1, STORED + MORE, trades, orders);
            js.killAndStart();
            assertEachOnceInOrder(js.url, trades, orders);
            trades.stop();
            orders.stop();
        }
    }

    /** A leader change mid-overflow: the follower applied the same records, held them unconfirmed past its ring, and
     *  is promoted after the leader and everything the leader sent are gone. */
    @Test
    void aMemberPromotedMidOverflowPublishesTheWholeUnstoredTail() throws Exception {
        try (RestartableNats js = new RestartableNats()) {
            js.addFeedStream();
            final TradeNatsPublisher oldTrades = new TradeNatsPublisher(js.url, "/trades", "e9", null, RING, 1);
            final OrderNatsPublisher oldOrders = new OrderNatsPublisher(js.url, "/orders", "e9", null, RING, 1);
            final TradeNatsPublisher trades = new TradeNatsPublisher(js.url, "/trades", "e9", null, RING, 1);
            final OrderNatsPublisher orders = new OrderNatsPublisher(js.url, "/orders", "e9", null, RING, 1);
            trades.role(false);
            orders.role(false);
            oldTrades.start();
            oldOrders.start();
            trades.start();
            orders.start();
            apply(1, STORED, oldTrades, oldOrders, trades, orders);
            awaitStored(js.url, STORED, STORED);
            js.pause();
            apply(STORED + 1, STORED + MORE, oldTrades, oldOrders, trades, orders);
            js.killAndStart();     // NATS never reads what the leader sent
            oldTrades.stop();      // the leader dies
            oldOrders.stop();
            trades.role(true);
            orders.role(true);
            apply(STORED + MORE + 1, STORED + MORE + 1, trades, orders);   // the first record of the new term
            assertEachOnceInOrder(js.url, STORED + MORE + 1, trades, orders);
            trades.stop();
            orders.stop();
        }
    }

    /** Sides and order events {@code from..to} on every bridge given (trade bridges first), paced under ~100K/s each. */
    static void apply(final int from, final int to, final Object... bridges) throws InterruptedException {
        for (int seq = from; seq <= to; seq++) {
            for (final Object b : bridges) {
                if (b instanceof TradeNatsPublisher t) {
                    t.offer(seq, 7, "IBM", (byte) (seq & 1), 1, 100_000_000L, seq, 0L);
                } else {
                    final finos.traderx.ordermatcher.lmax.OutputEvent o = new finos.traderx.ordermatcher.lmax.OutputEvent();
                    o.kind = finos.traderx.ordermatcher.lmax.OutputEvent.KIND_ORDER_ACCEPTED;
                    o.orderRef = seq;
                    o.inputSeq = seq;
                    o.accountId = 7;
                    o.quantity = 10;
                    o.remainingQty = 10;
                    ((OrderNatsPublisher) b).offer(o, "IBM", 0L, 0);
                }
            }
            if (seq % 100 == 0) { Thread.sleep(1); }
        }
    }

    static void assertEachOnceInOrder(final String url, final TradeNatsPublisher trades, final OrderNatsPublisher orders) throws Exception {
        assertEachOnceInOrder(url, STORED + MORE, trades, orders);
    }

    static void assertEachOnceInOrder(final String url, final int n, final TradeNatsPublisher trades, final OrderNatsPublisher orders) throws Exception {
        awaitStored(url, n, n);
        Thread.sleep(1_000);     // a duplicate would land after the counts are reached
        try (Connection nc = Nats.connect(url)) {
            assertEquals(runs(seqs(1, n)), runs(stored(nc, "/trades", "id").stream()
                .map(i -> Long.parseLong(i.substring(0, i.indexOf('-')))).toList()),
                "each trade side once, in apply order (stored runs): " + trades.json());
            assertEquals(runs(seqs(1, n)), runs(stored(nc, "/orders", "consensusSequence").stream().map(Long::parseLong).toList()),
                "each order event once, in apply order (stored runs): " + orders.json());
        }
        assertEquals(0, trades.dropped() + orders.dropped() + trades.gaps() + orders.gaps() + trades.rejected() + orders.rejected(),
            trades.json() + " " + orders.json());
        // The ring was exceeded and the spool carried the rest: without this the cases above prove nothing about it.
        assertTrue(spooled(trades.json()) > MORE - RING && spooled(orders.json()) > MORE - RING,
            "more than a ring was held on disk: " + trades.json() + " " + orders.json());
    }

    static long spooled(final String json) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"spooled\":(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    static List<Long> seqs(final long from, final long to) {
        final List<Long> out = new ArrayList<>();
        for (long s = from; s <= to; s++) { out.add(s); }
        return out;
    }

    /** A sequence list as its contiguous runs, "1..100 369029..500100": the whole list, readable when it differs. */
    static String runs(final List<Long> seqs) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < seqs.size(); ) {
            int j = i;
            while (j + 1 < seqs.size() && seqs.get(j + 1) == seqs.get(j) + 1) { j++; }
            b.append(b.length() == 0 ? "" : " ").append(seqs.get(i)).append("..").append(seqs.get(j));
            i = j + 1;
        }
        return b.toString();
    }

    static void awaitStored(final String url, final int trades, final int orders) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        try (Connection nc = Nats.connect(url)) {
            while ((count(nc, "/trades") < trades || count(nc, "/orders") < orders) && System.nanoTime() < deadline) {
                Thread.sleep(250);
            }
        }
    }

    /** Records stored on {@code subject}: the messages' record counts, read from the stream. */
    static long count(final Connection nc, final String subject) throws Exception {
        return stored(nc, subject, "accountId").size();
    }

    /** One field of every record stored on {@code subject}, in stream order. */
    static List<String> stored(final Connection nc, final String subject, final String field) throws Exception {
        final List<String> out = new ArrayList<>();
        final JetStreamSubscription sub = nc.jetStream().subscribe(subject,
            PushSubscribeOptions.builder().stream("TRADERX_FEED").ordered(true).build());
        for (Message m; (m = sub.nextMessage(Duration.ofMillis(500))) != null; ) {
            for (final byte[] record : ReceiptLanes.records(m.getData())) { out.add(ReceiptLanes.field(record, field)); }
        }
        sub.unsubscribe();
        return out;
    }
}
