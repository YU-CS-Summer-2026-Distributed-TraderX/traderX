package finos.traderx.ordermatcher.cluster;

import com.sun.net.httpserver.HttpServer;
import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.RestingOrder;
import finos.traderx.ordermatcher.lmax.SwapConventions;
import finos.traderx.ordermatcher.risk.BlpRiskState;
import finos.traderx.ordermatcher.risk.RiskMetrics;
import finos.traderx.ordermatcher.risk.RiskReason;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/** FR-RCM01/02/03: actual member HTTP handler and its cold formatter, synthetic accounting only. */
class RiskCapacityMetricsTest {
    private static final int A = 101;
    private static final int B = 202;
    private static final long PX = 100_000_000L;
    private static final String TOTAL = "traderx_risk_reserved_notional_ticks";
    private static final String RESERVED = "traderx_risk_account_reserved_notional_ticks";
    private static final String EXECUTED = "traderx_risk_account_executed_notional_ticks";
    private long timestamp = 1_000_000_000_000L;

    @Test void coldMemberIsUnavailableAndHasNoMoneySamples() throws Exception {
        try (final Endpoint endpoint = new Endpoint(new MatchingEngineClusteredService())) {
            final String body = endpoint.scrape();
            assertEquals(0, member(body, "traderx_risk_state_available"));
            assertFalse(samples(body).keySet().stream().anyMatch(s -> s.contains("notional")));
            assertFalse(samples(body).keySet().stream().anyMatch(s -> s.startsWith("traderx_risk_accounts{")));
        }
    }

    @Test void initializedEmptyRiskIsAvailableWithRealZero() throws Exception {
        final var service = new MatchingEngineClusteredService();
        service.initEngine(32, 32);
        try (final Endpoint endpoint = new Endpoint(service)) {
            final String body = endpoint.scrape();
            assertEquals(1, member(body, "traderx_risk_state_available"));
            assertEquals(0, member(body, "traderx_risk_accounts"));
            assertEquals(0, member(body, TOTAL));
            assertEquals(0, accountSamples(body, RESERVED));
        }
    }

    @Test void actualOrderPartialCrossAndCancelKeepZeroLabelsAndExecutedExposure() throws Exception {
        final var service = seeded();
        try (final Endpoint endpoint = new Endpoint(service)) {
            apply(service, order(A, (byte)0, 10, PX));
            final int buyRef = (int)service.nextOrderRef() - 1;
            String body = endpoint.scrape();
            assertEquals(10 * PX, member(body, TOTAL));
            assertEquals(10 * PX, account(body, RESERVED, A));
            assertEquals(0, account(body, EXECUTED, A));
            apply(service, order(B, (byte)1, 4, PX));
            body = endpoint.scrape();
            assertEquals(2, service.engine().tradeCounter(), "nonempty actual paired trade legs");
            assertEquals(6 * PX, member(body, TOTAL));
            assertEquals(6 * PX, account(body, RESERVED, A));
            assertEquals(0, account(body, RESERVED, B));
            assertEquals(4 * PX, account(body, EXECUTED, A));
            assertEquals(4 * PX, account(body, EXECUTED, B));
            final var cancel = new InputEvent();
            cancel.type = InputEvent.TYPE_ORDER_CANCEL;
            cancel.orderRef = buyRef;
            cancel.accountId = A;
            apply(service, cancel);
            body = endpoint.scrape();
            assertEquals(0, member(body, TOTAL));
            assertEquals(0, account(body, RESERVED, A));
            assertEquals(4 * PX, account(body, EXECUTED, A));
            assertEquals(2, accountSamples(body, RESERVED));
            apply(service, cancel);
            assertEquals(samples(body), samples(endpoint.scrape()), "duplicate cancel adds no exposure");
        }
    }

    @Test void twoUncrossedAccountsSumAtRestAndFullFillReleasesBoth() throws Exception {
        final var service = seeded();
        final var risk = service.engine().riskState();
        final var buy = reserve(risk, A, (byte)0, 2, PX);
        final var sell = reserve(risk, B, (byte)1, 3, PX);
        try (final Endpoint endpoint = new Endpoint(service)) {
            String body = endpoint.scrape();
            assertEquals(5 * PX, member(body, TOTAL));
            assertEquals(2 * PX, account(body, RESERVED, A));
            assertEquals(3 * PX, account(body, RESERVED, B));
            risk.consume(A, 0, (byte)0, buy, 2, PX - 1_000_000);
            risk.consume(B, 0, (byte)1, sell, 3, PX + 1_000_000);
            body = endpoint.scrape();
            assertEquals(0, member(body, TOTAL));
            assertEquals(0, account(body, RESERVED, A));
            assertEquals(0, account(body, RESERVED, B));
            assertEquals(198_000_000, account(body, EXECUTED, A));
            assertEquals(303_000_000, account(body, EXECUTED, B));
            risk.consume(A, 0, (byte)0, buy, 2, PX);
            assertEquals(samples(body), samples(endpoint.scrape()));
        }
    }

    @Test void disabledOccupiedAccountRetainsReservationAndUnknownRefusalsCreateNoLabels() throws Exception {
        final var service = seeded();
        final var risk = service.engine().riskState();
        final var resting = reserve(risk, A, (byte)0, 2, PX);
        risk.putAccount(A, false);
        try (final Endpoint endpoint = new Endpoint(service)) {
            final String before = endpoint.scrape();
            assertEquals(2 * PX, account(before, RESERVED, A));
            assertEquals(RiskReason.ACCOUNT_DISABLED, decision(risk, A, new RestingOrder()));
            for (int unknown = 10_000; unknown < 10_100; unknown++) {
                assertEquals(RiskReason.UNKNOWN_ACCOUNT, decision(risk, unknown, new RestingOrder()));
            }
            assertEquals(samples(before), samples(endpoint.scrape()));
            assertEquals(2, accountSamples(before, RESERVED));
            risk.release(A, 0, (byte)0, resting);
            assertEquals(0, account(endpoint.scrape(), RESERVED, A));
        }
    }

    @Test void restrictedRefusalDoesNotReserveAndReleaseRemainsVisible() throws Exception {
        final var service = seeded();
        final var risk = service.engine().riskState();
        final var resting = reserve(risk, A, (byte)0, 2, PX);
        try (final Endpoint endpoint = new Endpoint(service)) {
            final String before = endpoint.scrape();
            risk.putRestriction(0, true);
            assertEquals(RiskReason.RESTRICTED, decision(risk, B, new RestingOrder()));
            assertEquals(samples(before), samples(endpoint.scrape()));
            risk.release(A, 0, (byte)0, resting);
            risk.release(A, 0, (byte)0, resting);
            assertEquals(0, member(endpoint.scrape(), TOTAL));
        }
    }

    @Test void actualSelfMatchPreventionReleasesRestingReservationWithoutExecution() throws Exception {
        final var service = seeded();
        final var risk = service.engine().riskState();
        risk.putSelfMatchGroup(A, 7);
        risk.putSelfMatchGroup(B, 7);
        try (final Endpoint endpoint = new Endpoint(service)) {
            apply(service, order(A, (byte)0, 2, PX));
            assertEquals(2 * PX, account(endpoint.scrape(), RESERVED, A));
            apply(service, order(B, (byte)1, 3, PX));
            final String body = endpoint.scrape();
            assertEquals(1, service.engine().countSelfTradesPrevented());
            assertEquals(0, service.engine().tradeCounter());
            assertEquals(0, account(body, RESERVED, A));
            assertEquals(3 * PX, account(body, RESERVED, B));
            assertEquals(3 * PX, member(body, TOTAL));
            assertEquals(0, account(body, EXECUTED, A));
            assertEquals(0, account(body, EXECUTED, B));
        }
    }

    @Test void formatterUsesMultiplierForReservationAndActualFillPriceForExecution() {
        final var risk = smallRisk();
        risk.putAccount(A, true);
        risk.putSecurity(0, true);
        risk.putContractMultiplier(0, 100);
        final var resting = reserve(risk, A, (byte)0, 2, 5_000_000);
        String body = ClusterNodeMain.riskCapacityMetrics(1, risk);
        assertEquals(1_000_000_000, account(body, RESERVED, A));
        risk.consume(A, 0, (byte)0, resting, 1, 4_000_000);
        body = ClusterNodeMain.riskCapacityMetrics(1, risk);
        assertEquals(500_000_000, account(body, RESERVED, A));
        assertEquals(400_000_000, account(body, EXECUTED, A));
    }

    @Test void actualNonUsdSwapUsesSequencedFxAndMissingRateConsumesNothing() throws Exception {
        final var service = seeded();
        final var booking = new InputEvent();
        booking.type = InputEvent.TYPE_SWAP_BOOK;
        booking.accountId = A;
        booking.side = InputEvent.SWAP_RECEIVE_FIXED;
        booking.qty = 1000;
        booking.limitPx = 42_000;
        booking.securityId = SwapConventions.indexOf("EUR-ESTR-1Y-ACT360");
        booking.setSwapDates((int)LocalDate.of(2026, 1, 1).toEpochDay(),
            (int)LocalDate.of(2027, 1, 1).toEpochDay());
        try (final Endpoint endpoint = new Endpoint(service)) {
            apply(service, booking);
            assertEquals(0, service.contractCount());
            assertEquals(0, account(endpoint.scrape(), EXECUTED, A));
            final var fx = new InputEvent();
            fx.type = InputEvent.TYPE_FX_RATE;
            fx.securityId = SwapConventions.currencyIndexOf("EUR");
            fx.limitPx = 1_100_000;
            apply(service, fx);
            apply(service, booking);
            final String body = endpoint.scrape();
            assertEquals(1, service.contractCount());
            assertEquals(1_100_000_000, account(body, EXECUTED, A));
            assertEquals(0, account(body, RESERVED, A));
            assertEquals(0, member(body, TOTAL));
        }
    }

    @Test void labelsBoundedByOccupiedTableSlotsAndEmptyStateDoesNotRememberOldLabels() {
        final var risk = smallRisk(); // maxAccounts=2 produces four backing slots.
        for (int id = 1; id <= 4; id++) risk.putAccount(id, id != 4);
        final String body = ClusterNodeMain.riskCapacityMetrics(1, risk);
        assertEquals(4, member(body, "traderx_risk_accounts"));
        assertEquals(4, accountSamples(body, RESERVED));
        assertEquals(4, accountSamples(body, EXECUTED));
        for (int id = 1; id <= 4; id++) assertEquals(0, account(body, RESERVED, id));
        final String replacement = ClusterNodeMain.riskCapacityMetrics(1, smallRisk());
        assertEquals(0, accountSamples(replacement, RESERVED));
        assertEquals(0, member(replacement, TOTAL));
    }

    @Test void sameHttpHandlerReadsReplacementStateWithoutRetainingAccountLabels() throws Exception {
        final var service = seeded();
        reserve(service.engine().riskState(), A, (byte)0, 2, PX);
        try (final Endpoint endpoint = new Endpoint(service)) {
            assertEquals(2 * PX, account(endpoint.scrape(), RESERVED, A));
            service.initEngine(32, 32); // existing reset primitive, only on this owned test service.
            final String body = endpoint.scrape();
            assertEquals(1, member(body, "traderx_risk_state_available"));
            assertEquals(0, member(body, "traderx_risk_accounts"));
            assertEquals(0, member(body, TOTAL));
            assertEquals(0, accountSamples(body, RESERVED));
            assertFalse(body.contains("account=\"" + A + "\""));
        }
    }

    @Test void saturatedAggregateIsTheExistingAuthoritativeValueAndRemainsAGauge() {
        final var risk = smallRisk();
        risk.reaccumulateReservation(A, 0, (byte)0, Long.MAX_VALUE - 1, 1);
        risk.reaccumulateReservation(B, 0, (byte)1, 10, 1);
        final String body = ClusterNodeMain.riskCapacityMetrics(1, risk);
        assertEquals(Long.MAX_VALUE, member(body, TOTAL));
        assertEquals(Long.MAX_VALUE - 1, account(body, RESERVED, A));
        assertEquals(10, account(body, RESERVED, B));
        assertTrue(body.contains("# TYPE " + TOTAL + " gauge\n"));
    }

    @Test void creditRefusalLeavesNonzeroExistingReservationUntouched() {
        final var risk = new BlpRiskState(2, 1, 8, 8, 250_000_000, 10, 250_000_000,
            10_000, new RiskMetrics());
        risk.putAccount(A, true);
        risk.putSecurity(0, true);
        reserve(risk, A, (byte)0, 2, PX);
        final String before = ClusterNodeMain.riskCapacityMetrics(1, risk);
        assertEquals(RiskReason.CREDIT_LIMIT, decision(risk, A, new RestingOrder()));
        assertEquals(samples(before), samples(ClusterNodeMain.riskCapacityMetrics(1, risk)));
        assertEquals(2 * PX, member(before, TOTAL));
    }

    @Test void nullRiskFormatterIsUnavailableAndMemberLabelsKeepReplicasSeparate() {
        final String unavailable = ClusterNodeMain.riskCapacityMetrics(2, null);
        assertEquals(Map.of("traderx_risk_state_available{member=\"2\"}", 0L), samples(unavailable));
        final var risk = smallRisk();
        risk.putAccount(A, true);
        risk.putSecurity(0, true);
        reserve(risk, A, (byte)0, 2, PX);
        final String one = ClusterNodeMain.riskCapacityMetrics(1, risk);
        final String two = ClusterNodeMain.riskCapacityMetrics(2, risk);
        assertEquals(2 * PX, member(one, TOTAL));
        assertEquals(2 * PX, samples(two).get(TOTAL + "{member=\"2\"}"));
        assertTrue(one.contains("do not sum replicas"));
    }

    private MatchingEngineClusteredService seeded() {
        final var service = new MatchingEngineClusteredService();
        service.initEngine(32, 32);
        final var risk = service.engine().riskState();
        risk.putAccount(A, true);
        risk.putAccount(B, true);
        risk.putSecurity(0, true);
        return service;
    }

    private static BlpRiskState smallRisk() {
        return new BlpRiskState(2, 1, 8, 8, Long.MAX_VALUE / 4, 1000, Long.MAX_VALUE / 4,
            10_000, new RiskMetrics());
    }

    private static RestingOrder reserve(final BlpRiskState risk, final int account,
                                        final byte side, final int qty, final long px) {
        final var resting = new RestingOrder();
        assertEquals(RiskReason.ACCEPTED, risk.decideAndReserve(0, 0, 1, account, 0, side,
            0, qty, px, 1, resting));
        assertEquals(qty, resting.reservedQty());
        return resting;
    }

    private static RiskReason decision(final BlpRiskState risk, final int account,
                                       final RestingOrder resting) {
        return risk.decideAndReserve(0, 0, 2, account, 0, (byte)0, 0, 1, PX, 1, resting);
    }

    private static InputEvent order(final int account, final byte side, final int qty, final long px) {
        final var event = new InputEvent();
        event.type = InputEvent.TYPE_ORDER_NEW;
        event.accountId = account;
        event.securityId = 0;
        event.side = side;
        event.qty = qty;
        event.limitPx = px;
        return event;
    }

    private void apply(final MatchingEngineClusteredService service, final InputEvent event) {
        final var codec = new AeronReplicationCodec();
        final var buffer = new UnsafeBuffer(new byte[AeronReplicationCodec.INPUT_BYTES]);
        event.eventTimeMillis = ++timestamp;
        codec.encodeInput(buffer, 0, event, 0, 0, 0);
        service.onSessionMessage(null, timestamp, buffer, 0, AeronReplicationCodec.INPUT_BYTES, null);
    }

    /** Parse real samples; missing or duplicate series are failures, never interpreted as zero. */
    private static Map<String, Long> samples(final String body) {
        final Map<String, Long> result = new HashMap<>();
        for (final String line : body.split("\n")) {
            if (!line.startsWith("traderx_risk_")) continue;
            final int space = line.lastIndexOf(' ');
            assertTrue(space > 0, line);
            assertNull(result.put(line.substring(0, space), Long.parseLong(line.substring(space + 1))), line);
        }
        return result;
    }

    private static long member(final String body, final String metric) {
        final String key = metric + "{member=\"1\"}";
        assertTrue(samples(body).containsKey(key), "missing " + key + " in " + body);
        return samples(body).get(key);
    }

    private static long account(final String body, final String metric, final int account) {
        final String key = metric + "{member=\"1\",account=\"" + account + "\"}";
        assertTrue(samples(body).containsKey(key), "missing " + key + " in " + body);
        return samples(body).get(key);
    }

    private static long accountSamples(final String body, final String metric) {
        return samples(body).keySet().stream().filter(s -> s.startsWith(metric + "{")).count();
    }

    /** Own disposable HTTP server with production handler; no cluster, retained rig, or custom renderer. */
    private static final class Endpoint implements AutoCloseable {
        private final HttpServer server;
        Endpoint(final MatchingEngineClusteredService service) throws Exception {
            final var factory = ClusterNodeMain.class.getDeclaredMethod("healthServer", int.class,
                int.class, List.class, MatchingEngineClusteredService.class, ClusterRecon.class);
            factory.setAccessible(true);
            server = (HttpServer)factory.invoke(null, 0, 1, List.of("localhost"), service, null);
        }
        String scrape() throws Exception {
            final var connection = (HttpURLConnection)URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + "/metrics").toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            try {
                assertEquals(200, connection.getResponseCode());
                assertEquals("text/plain; version=0.0.4", connection.getHeaderField("Content-Type"));
                try (var input = connection.getInputStream()) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            } finally { connection.disconnect(); }
        }
        @Override public void close() {
            server.stop(0);
            ((ExecutorService)server.getExecutor()).shutdownNow();
        }
    }
}
