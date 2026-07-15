package finos.traderx.positionservice.eod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import finos.traderx.positionservice.eod.EodPnlConsumer.PnlResult;
import finos.traderx.positionservice.model.Position;
import finos.traderx.positionservice.repository.PositionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.JetStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * YU06 (FR-EOD30-32, NFR-EOD05): the consumer's marking logic — mark-to-close math, per-account
 * fail-safe halt on a missing/flagged holding, and idempotent reprocessing. Drives {@code process}
 * directly with in-memory doubles (no NATS, no DB).
 */
class EodPnlConsumerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 8);

    private static final class FakePositions extends PositionRepository {
        final List<Position> all = new ArrayList<>();
        FakePositions() { super(null); }
        @Override public List<Position> findAll() { return all; }
        void add(int account, String security, int qty) {
            Position p = new Position();
            p.setAccountId(account);
            p.setSecurity(security);
            p.setQuantity(qty);
            p.setUpdated(new Date());
            all.add(p);
        }
    }

    private static final class FakeReader extends EodPriceSnapshotReader {
        final Map<String, EodSnapshotPrice> map = new HashMap<>();
        LocalDate requestedDate;
        int requestedVersion;
        FakeReader() { super(null); }
        @Override public Map<String, EodSnapshotPrice> read(LocalDate d, int v) {
            requestedDate = d;
            requestedVersion = v;
            return map;
        }
        void put(String security, String price, String quality) {
            map.put(security, new EodSnapshotPrice(security, price == null ? null : new BigDecimal(price), quality));
        }
    }

    private static final class FakePnl extends EodPnlRepository {
        final Map<String, Row> rows = new LinkedHashMap<>();
        FakePnl() { super(null); }
        @Override public void upsertAll(List<Row> in) {
            for (Row r : in) {
                rows.put(r.sessionDate() + "|" + r.version() + "|" + r.accountId() + "|" + r.security(), r);
            }
        }
    }

    private EodPnlConsumer consumer(FakePositions p, FakeReader r, FakePnl pnl) {
        return consumer(p, r, pnl, new SimpleMeterRegistry());
    }

    private EodPnlConsumer consumer(FakePositions p, FakeReader r, FakePnl pnl,
                                    SimpleMeterRegistry registry) {
        return new EodPnlConsumer(p, r, pnl, registry,
            "nats://localhost:4222", false, "TRADERX_EOD", "eod.prices.ready", "eod.pnl.done", "eod-pnl");
    }

    @Test
    void marksHoldingsToClosingPrice() {
        FakePositions p = new FakePositions();
        p.add(1, "AAA", 10);
        p.add(2, "BBB", -5); // short
        FakeReader r = new FakeReader();
        r.put("AAA", "100", "OK");
        r.put("BBB", "200", "OVERRIDDEN");
        FakePnl pnl = new FakePnl();

        PnlResult result = consumer(p, r, pnl).process(DATE, 3);

        assertEquals(2, result.accountsMarked());
        assertEquals(0, result.accountsHalted());
        assertEquals(new BigDecimal("1000"), pnl.rows.get(DATE + "|3|1|AAA").marketValue());
        assertEquals(new BigDecimal("-1000"), pnl.rows.get(DATE + "|3|2|BBB").marketValue());
    }

    @Test
    void haltsAccountWithSecurityMissingFromSnapshot() {
        FakePositions p = new FakePositions();
        p.add(1, "AAA", 10);
        p.add(1, "ZZZ", 5); // ZZZ not in the snapshot
        FakeReader r = new FakeReader();
        r.put("AAA", "100", "OK");
        FakePnl pnl = new FakePnl();

        PnlResult result = consumer(p, r, pnl).process(DATE, 1);

        assertEquals(0, result.accountsMarked());
        assertEquals(1, result.accountsHalted());
        assertTrue(pnl.rows.isEmpty(), "a halted account writes no rows");
    }

    @Test
    void haltsAccountWithFlaggedOrNullPrice() {
        FakePositions p = new FakePositions();
        p.add(1, "AAA", 10);
        FakeReader r = new FakeReader();
        r.put("AAA", null, "MISSING"); // defensive: never happens in a published snapshot, still halts
        FakePnl pnl = new FakePnl();

        PnlResult result = consumer(p, r, pnl).process(DATE, 1);

        assertEquals(0, result.accountsMarked());
        assertEquals(1, result.accountsHalted());
    }

    @Test
    void marksGoodAccountsWhileHaltingBadOnes() {
        FakePositions p = new FakePositions();
        p.add(1, "AAA", 10);   // fully priced -> marked
        p.add(2, "AAA", 3);
        p.add(2, "ZZZ", 3);    // ZZZ missing -> account 2 halted
        FakeReader r = new FakeReader();
        r.put("AAA", "100", "OK");
        FakePnl pnl = new FakePnl();

        PnlResult result = consumer(p, r, pnl).process(DATE, 1);

        assertEquals(1, result.accountsMarked());
        assertEquals(1, result.accountsHalted());
        assertEquals(1, pnl.rows.size());
        assertTrue(pnl.rows.containsKey(DATE + "|1|1|AAA"));
    }

    @Test
    void reprocessingIsIdempotent() {
        FakePositions p = new FakePositions();
        p.add(1, "AAA", 10);
        FakeReader r = new FakeReader();
        r.put("AAA", "100", "OK");
        FakePnl pnl = new FakePnl();
        EodPnlConsumer c = consumer(p, r, pnl);

        c.process(DATE, 1);
        c.process(DATE, 1); // durable redelivery

        assertEquals(1, pnl.rows.size(), "same (date,version,account,security) upserts to one row");
    }

    @Test
    void readsExactlyTheVersionNamedByTheGate() {
        FakePositions p = new FakePositions();
        p.add(1, "AAA", 1);
        FakeReader r = new FakeReader();
        r.put("AAA", "101", "OK");

        consumer(p, r, new FakePnl()).process(DATE, 7);

        assertEquals(DATE, r.requestedDate);
        assertEquals(7, r.requestedVersion);
    }

    @Test
    void emitsOneCompletionForTheExactDateAndVersion() throws Exception {
        EodPnlConsumer c = consumer(new FakePositions(), new FakeReader(), new FakePnl());
        JetStream js = mock(JetStream.class);
        Field field = EodPnlConsumer.class.getDeclaredField("jetStream");
        field.setAccessible(true);
        field.set(c, js);

        c.publishPnlDone(DATE, 4, new PnlResult(2, 1, 1234L));

        verify(js, times(1)).publish(anyString(), any(byte[].class), any());
    }

    @Test
    void metricsCountHaltedAndMarkedAccountsWithFixedCardinality() {
        FakePositions p = new FakePositions();
        p.add(1, "AAA", 1);
        p.add(2, "ZZZ", 1);
        FakeReader r = new FakeReader();
        r.put("AAA", "100", "OK");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        consumer(p, r, new FakePnl(), registry).process(DATE, 2);

        assertEquals(1.0, registry.get("traderx_eod_pnl_accounts_marked").counter().count());
        assertEquals(1.0, registry.get("traderx_eod_pnl_halted").counter().count());
        assertEquals(3, registry.getMeters().size());
        assertTrue(registry.getMeters().stream().allMatch(m -> m.getId().getTags().isEmpty()));
    }
}
