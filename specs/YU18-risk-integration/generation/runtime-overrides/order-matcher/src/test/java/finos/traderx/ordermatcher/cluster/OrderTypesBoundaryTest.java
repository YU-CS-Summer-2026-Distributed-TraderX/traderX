package finos.traderx.ordermatcher.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.ordermatcher.lmax.InputEvent;
import finos.traderx.ordermatcher.lmax.OrderTypes;
import finos.traderx.ordermatcher.lmax.OutputEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.Message;
import quickfix.MemoryStoreFactory;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.ExecType;
import quickfix.field.HandlInst;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;

import java.net.ServerSocket;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YU18 order types at the boundaries (FR-OT02..04, FR-OT41, SC-OT01..04, SC-OT48): the REST
 * parsing/validation, the FIX 4.4 mapping of all seven types, and F1 end to end over a real
 * QuickFIX/J session.
 */
@Timeout(60)
class OrderTypesBoundaryTest {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final long PX = 1_000_000L;
    private static final int TIF_TAG = 59;

    private static JsonNode json(final String s) throws Exception {
        return JSON.readTree(s);
    }

    /** A typed REST body with {@code quantity} added unless the case sets it itself. */
    private static Object typed(final String body, final int qty) throws Exception {
        final com.fasterxml.jackson.databind.node.ObjectNode n =
            (com.fasterxml.jackson.databind.node.ObjectNode) json(body);
        if (!n.has("quantity")) {
            n.put("quantity", qty);
        }
        return ClusterGatewayMain.parseTyped(n, InputEvent.SIDE_BUY);
    }

    // ===== REST (FR-OT02, FR-OT03) ==============================================================

    @Test
    void sc01_sc02_untypedRequestsKeepTodaysInferenceExceptANegativePrice() throws Exception {
        assertNull(ClusterGatewayMain.legacyOrderError(json("{\"limitPrice\":0}"), 0L), "console market");
        assertNull(ClusterGatewayMain.legacyOrderError(json("{}"), 0L), "omitted price: market");
        assertNull(ClusterGatewayMain.legacyOrderError(json("{\"limitPrice\":101.5}"), 101_500_000L));
        assertEquals(OrderTypes.reasonText(OrderTypes.LEGACY_NEGATIVE_PRICE),
            ClusterGatewayMain.legacyOrderError(json("{\"limitPrice\":-1}"), -1_000_000L));
        assertEquals("timeInForce requires orderType",
            ClusterGatewayMain.legacyOrderError(json("{\"limitPrice\":1,\"timeInForce\":\"IOC\"}"), PX));
        assertEquals("stopPrice requires orderType",
            ClusterGatewayMain.legacyOrderError(json("{\"stopPrice\":1}"), 0L));
    }

    @Test
    void sc03_typedValidationRefusesEveryMisShapedRequest() throws Exception {
        final String[][] refused = {
            { "{\"orderType\":\"MARKET\",\"limitPrice\":1}", "field not allowed for this orderType" },
            { "{\"orderType\":\"LIMIT\",\"limitPrice\":1,\"stopPrice\":1}", "field not allowed for this orderType" },
            { "{\"orderType\":\"STOP_LIMIT\",\"stopPrice\":1}", "limitPrice required" },
            { "{\"orderType\":\"ICEBERG\",\"limitPrice\":1,\"displayQuantity\":10}", "displayQuantity must be positive and below quantity" },
            { "{\"orderType\":\"PEGGED\",\"pegReference\":\"MARKET\",\"limitPrice\":1}", "pegReference must be PRIMARY or MIDPOINT" },
            { "{\"orderType\":\"MARKET\",\"timeInForce\":\"GTC\"}", "timeInForce not allowed for this orderType" },
            { "{\"orderType\":\"STOP\",\"stopPrice\":1,\"timeInForce\":\"IOC\"}", "timeInForce not allowed for this orderType" },
            { "{\"orderType\":\"TRAILING_STOP\",\"trailAmount\":1,\"trailPercentBps\":10}", "exactly one of trailAmount (positive) or trailPercentBps (1-5000) required" },
            { "{\"orderType\":\"TRAILING_STOP\",\"trailPercentBps\":5001}", "exactly one of trailAmount (positive) or trailPercentBps (1-5000) required" },
            { "{\"orderType\":\"PEGGED\",\"pegReference\":\"PRIMARY\",\"limitPrice\":1,\"pegOffset\":1}", "pegOffset must not be more aggressive than the reference (buy <= 0, sell >= 0) and within 10000 ticks" },
            { "{\"orderType\":\"LIMIT\",\"limitPrice\":-2}", "price must be positive and within range" },
            { "{\"orderType\":\"WHATEVER\"}", "unknown orderType" },
        };
        for (final String[] c : refused) {
            assertEquals(c[1], typed(c[0], 10), c[0]);
        }
        final Object stop = typed("{\"orderType\":\"STOP\",\"stopPrice\":101}", 10);
        final OrderSubmitter.TypedOrder t = assertInstanceOf(OrderSubmitter.TypedOrder.class, stop);
        assertEquals(OrderTypes.GTC, t.tif(), "typed default TIF");
        assertEquals(101 * PX, t.stopPx());
        assertEquals(OrderTypes.IOC, ((OrderSubmitter.TypedOrder) typed("{\"orderType\":\"MARKET\"}", 1)).tif());
    }

    /** Review I3: presence and JSON type are judged before any coercion, so nothing is truncated,
     *  stringly coerced or hidden behind a sentinel zero. */
    static final String[][] INEXACT_REST = {
        { "{\"orderType\":\"MARKET\",\"quantity\":1.00000000000000001}", "quantity must be a whole number" },
        { "{\"orderType\":\"LIMIT\",\"limitPrice\":100.000000000000001}", "limitPrice allows at most 6 decimal places" },
        { "{\"orderType\":\"STOP\",\"stopPrice\":100.000000000000001}", "stopPrice allows at most 6 decimal places" },
        { "{\"orderType\":\"ICEBERG\",\"limitPrice\":100,\"displayQuantity\":3.00000000000000001}", "displayQuantity must be a whole number" },
        { "{\"orderType\":\"PEGGED\",\"pegReference\":\"PRIMARY\",\"limitPrice\":100,\"pegOffset\":-1.00000000000000001}", "pegOffset must be a whole number" },
        { "{\"orderType\":\"TRAILING_STOP\",\"trailPercentBps\":1.00000000000000001}", "trailPercentBps must be a whole number" },
        { "{\"orderType\":\"TRAILING_STOP\",\"trailAmount\":1.00000000000000001}", "trailAmount allows at most 6 decimal places" },
        { "{\"orderType\":\"MARKET\",\"quantity\":1.00000000000000001e0}", "quantity must be a whole number" },

        { "{\"orderType\":\"TRAILING_STOP\",\"trailPercentBps\":1.9}", "trailPercentBps must be a whole number" },
        { "{\"orderType\":\"ICEBERG\",\"limitPrice\":100,\"displayQuantity\":3.8}", "displayQuantity must be a whole number" },
        { "{\"orderType\":\"MARKET\",\"limitPrice\":0}", "field not allowed for this orderType" },
        { "{\"orderType\":\"MARKET\",\"limitPrice\":null}", "field not allowed for this orderType" },
        { "{\"orderType\":\"LIMIT\",\"limitPrice\":1,\"stopPrice\":0}", "field not allowed for this orderType" },
        { "{\"orderType\":\"STOP\",\"stopPrice\":1,\"displayQuantity\":0}", "field not allowed for this orderType" },
        { "{\"orderType\":\"LIMIT\",\"limitPrice\":1,\"pegOffset\":0}", "field not allowed for this orderType" },
        { "{\"orderType\":\"STOP\",\"stopPrice\":0}", "price must be positive and within range" },
        { "{\"orderType\":\"LIMIT\",\"limitPrice\":1,\"quantity\":10.5}", "quantity must be a whole number" },
        { "{\"orderType\":\"LIMIT\",\"limitPrice\":1,\"quantity\":\"10\"}", "quantity must be a number" },
        { "{\"orderType\":\"LIMIT\",\"limitPrice\":1,\"quantity\":1e12}", "quantity must be positive" },
        { "{\"orderType\":\"LIMIT\",\"limitPrice\":\"101\"}", "limitPrice must be a number" },
        { "{\"orderType\":\"LIMIT\",\"limitPrice\":true}", "limitPrice must be a number" },
        { "{\"orderType\":\"LIMIT\",\"limitPrice\":101.1234567}", "limitPrice allows at most 6 decimal places" },
        { "{\"orderType\":\"LIMIT\",\"limitPrice\":1e300}", "price must be positive and within range" },
        { "{\"orderType\":\"PEGGED\",\"pegReference\":\"PRIMARY\",\"limitPrice\":1,\"pegOffset\":-1.5}", "pegOffset must be a whole number" },
        { "{\"orderType\":\"PEGGED\",\"pegReference\":\"PRIMARY\",\"limitPrice\":1,\"pegOffset\":-1e12}", "pegOffset must not be more aggressive than the reference (buy <= 0, sell >= 0) and within 10000 ticks" },
        { "{\"orderType\":\"PEGGED\",\"pegReference\":1,\"limitPrice\":1}", "pegReference must be PRIMARY or MIDPOINT" },
        { "{\"orderType\":\"TRAILING_STOP\",\"trailPercentBps\":1e12}", "exactly one of trailAmount (positive) or trailPercentBps (1-5000) required" },
        { "{\"orderType\":\"TRAILING_STOP\",\"trailAmount\":0.0000001}", "trailAmount allows at most 6 decimal places" },
        { "{\"orderType\":\"ICEBERG\",\"limitPrice\":100,\"displayQuantity\":1e20}", "displayQuantity must be positive and below quantity" },
        { "{\"orderType\":null}", "unknown orderType" },
        { "{\"orderType\":7}", "unknown orderType" },
        { "{\"orderType\":\"LIMIT\",\"limitPrice\":1,\"timeInForce\":3}", "timeInForce not allowed for this orderType" },
        { "{\"orderType\":\"LIMIT\",\"limitPrice\":1,\"timeInForce\":null}", "timeInForce not allowed for this orderType" },
    };

    @Test
    void i3_restMapperIsExactAboutTypeAndPresence() throws Exception {
        for (final String[] c : INEXACT_REST) {
            assertEquals(c[1], typed(c[0], 10), c[0]);
        }
        // Exact values survive whole: six decimals, integral-valued decimals, zero peg offset.
        final OrderSubmitter.TypedOrder lim = assertInstanceOf(OrderSubmitter.TypedOrder.class,
            typed("{\"orderType\":\"LIMIT\",\"limitPrice\":101.123456}", 10));
        assertEquals(101_123_456L, lim.limitPx());
        final OrderSubmitter.TypedOrder ice = assertInstanceOf(OrderSubmitter.TypedOrder.class,
            typed("{\"orderType\":\"ICEBERG\",\"limitPrice\":100,\"displayQuantity\":3.0}", 10));
        assertEquals(3, ice.displayQty());
        final OrderSubmitter.TypedOrder bps = assertInstanceOf(OrderSubmitter.TypedOrder.class,
            typed("{\"orderType\":\"TRAILING_STOP\",\"trailPercentBps\":150}", 10));
        assertEquals(150L, bps.trailValue());
        assertInstanceOf(OrderSubmitter.TypedOrder.class,
            typed("{\"orderType\":\"PEGGED\",\"pegReference\":\"MIDPOINT\",\"limitPrice\":1,\"pegOffset\":0}", 10));
        // The approved untyped compatibility is untouched: limitPrice 0 without orderType is a market.
        assertNull(ClusterGatewayMain.legacyOrderError(json("{\"limitPrice\":0}"), 0L));
    }

    /**
     * Review I3, route level: each inexact typed request through the real /orders and /replace
     * handlers answers 422 with the mapper's text and never reaches the owner thread's offer
     * queue. A valid request through the same route DOES reach it (the positive control), so an
     * empty queue is evidence, not an unobservable default.
     */
    @Test
    void i3_restRoutesRefuseWithoutSubmitting() throws Exception {
        final ClusterGatewayMain gw = new ClusterGatewayMain();
        final java.lang.reflect.Field tasksField = ClusterGatewayMain.class.getDeclaredField("tasks");
        tasksField.setAccessible(true);
        final java.util.Queue<?> tasks = (java.util.Queue<?>) tasksField.get(gw);
        final com.sun.net.httpserver.HttpServer server =
            com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        for (final String[] route : new String[][] { { "/orders", "handleOrder" }, { "/replace", "handleReplace" } }) {
            final java.lang.reflect.Method h = ClusterGatewayMain.class.getDeclaredMethod(route[1],
                com.sun.net.httpserver.HttpExchange.class);
            h.setAccessible(true);
            server.createContext(route[0], ex -> {
                try {
                    h.invoke(gw, ex);
                } catch (final ReflectiveOperationException e) {
                    throw new java.io.IOException(e);
                }
            });
        }
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "i3-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        final String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            for (final String[] c : INEXACT_REST) {
                for (final String route : new String[] { "/orders", "/replace" }) {
                    // Send the original numeric lexemes; a test-side double round trip would
                    // erase exactly the invalid precision this test must catch.
                    final String body = c[0].substring(0, c[0].length() - 1)
                        + ",\"ticker\":\"IBM\",\"side\":\"Buy\",\"accountId\":11,\"orderRef\":5"
                        + (json(c[0]).has("quantity") ? "" : ",\"quantity\":10") + "}";
                    final java.net.http.HttpResponse<String> r = http.send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + route))
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                    final String what = route + " " + c[0];
                    assertEquals(422, r.statusCode(), what + " -> " + r.body());
                    assertEquals(c[1], json(r.body()).path("error").asText(), what);
                    assertTrue(tasks.isEmpty(), what + ": nothing offered");
                }
            }
            // Positive control: a valid typed order through the same route is queued for offer.
            http.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/orders"))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                    "{\"orderType\":\"LIMIT\",\"limitPrice\":1,\"quantity\":10,\"ticker\":\"IBM\",\"side\":\"Buy\",\"accountId\":11}"))
                .build(), java.net.http.HttpResponse.BodyHandlers.discarding());
            final long deadline = System.currentTimeMillis() + 5_000;
            while (tasks.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, tasks.size(), "a valid typed order reaches the offer queue");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void i3_wireReaderPreservesExactTypedValuesAndLegacyNodeConversions() throws Exception {
        final ObjectMapper legacyMapper = new ObjectMapper();
        for (final String raw : new String[] {
            "{\"quantity\":1.00000000000000001,\"limitPrice\":100.000000000000001}",
            "{\"quantity\":1e300,\"limitPrice\":1e-300}",
            "{\"quantity\":\"10\",\"limitPrice\":0}",
            "{\"quantity\":10.9,\"limitPrice\":101.1234567}"
        }) {
            final JsonNode expected = legacyMapper.readTree(raw);
            final JsonNode actual = ClusterGatewayMain.readOrderBody(new java.io.ByteArrayInputStream(
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(expected, actual, "untyped node types and values stay identical: " + raw);
            assertEquals(expected.path("quantity").asInt(), actual.path("quantity").asInt());
            assertEquals(expected.path("limitPrice").asDouble(), actual.path("limitPrice").asDouble());
        }
        final String raw = "{\"orderType\":\"LIMIT\",\"quantity\":1e1,\"limitPrice\":101.123456000}";
        final JsonNode exact = ClusterGatewayMain.readOrderBody(new java.io.ByteArrayInputStream(
            raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        final OrderSubmitter.TypedOrder order = assertInstanceOf(OrderSubmitter.TypedOrder.class,
            ClusterGatewayMain.parseTyped(exact, InputEvent.SIDE_BUY));
        assertEquals(101_123_456L, order.limitPx());
        assertEquals(10, exact.path("quantity").asInt());
    }

    // ===== ack routing (review I1) =============================================================

    /** Every egress kind the gateway routes on is unique: it dispatches on the kind byte alone. */
    @Test
    void i1_everyEgressKindIsUnique() throws Exception {
        final java.util.Map<Byte, String> seen = new java.util.HashMap<>();
        for (final Class<?> c : new Class<?>[] { OutputEvent.class, MatchingEngineClusteredService.class }) {
            for (final java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getName().startsWith("KIND_") && f.getType() == byte.class
                    && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    final String prior = seen.put(f.getByte(null), c.getSimpleName() + "." + f.getName());
                    assertNull(prior, f.getName() + " shares " + f.getByte(null) + " with " + prior);
                }
            }
        }
        assertTrue(seen.size() >= 15, "the audit saw the kinds: " + seen);
        assertEquals(104, MatchingEngineClusteredService.KIND_SANDBOX_RESET, "legacy reset identity kept");
    }

    /**
     * Review I1, through the gateway's real {@code onEgress}: business-day, sandbox-reset and
     * session-phase acks, interleaved, each complete only their own waiter, by their own id.
     */
    @Test
    void i1_gatewayRoutesDayResetAndPhaseAcksToTheirOwnWaiters() throws Exception {
        final ClusterGatewayMain gw = new ClusterGatewayMain();
        final java.lang.reflect.Method onEgress = ClusterGatewayMain.class.getDeclaredMethod("onEgress",
            long.class, long.class, org.agrona.DirectBuffer.class, int.class, int.class,
            io.aeron.logbuffer.Header.class);
        onEgress.setAccessible(true);
        final byte[] kinds = { MatchingEngineClusteredService.KIND_BUSINESS_DAY,
            MatchingEngineClusteredService.KIND_SANDBOX_RESET, MatchingEngineClusteredService.KIND_BUSINESS_DAY,
            MatchingEngineClusteredService.KIND_SESSION_PHASE, MatchingEngineClusteredService.KIND_SANDBOX_RESET };
        final java.util.Map<Byte, String> field = java.util.Map.of(
            MatchingEngineClusteredService.KIND_BUSINESS_DAY, "lastBusinessDayAck",
            MatchingEngineClusteredService.KIND_SANDBOX_RESET, "lastResetAck",
            MatchingEngineClusteredService.KIND_SESSION_PHASE, "lastSessionAck");
        final java.util.Map<String, Long> expected = new java.util.HashMap<>();
        for (int i = 0; i < kinds.length; i++) {
            final org.agrona.concurrent.UnsafeBuffer b =
                new org.agrona.concurrent.UnsafeBuffer(new byte[MatchingEngineClusteredService.EGRESS_ACK_LENGTH]);
            final long requestId = 900 + i;
            b.putLong(0, 50 + i);      // appliedSeq
            b.putByte(12, kinds[i]);
            b.putLong(13, requestId);
            onEgress.invoke(gw, 0L, 0L, b, 0, MatchingEngineClusteredService.EGRESS_ACK_LENGTH, null);
            expected.put(field.get(kinds[i]), requestId);
            for (final String name : field.values()) {
                final java.lang.reflect.Field f = ClusterGatewayMain.class.getDeclaredField(name);
                f.setAccessible(true);
                final long[] ack = (long[]) f.get(gw);
                final Long want = expected.get(name);
                if (want == null) {
                    assertNull(ack, name + " untouched after ack " + i);
                } else {
                    assertNotNull(ack, name + " after ack " + i);
                    assertEquals(want, ack[2], name + " holds its own request id after ack " + i);
                }
            }
        }
    }

    @Test
    void businessDatesMustBeCalendarDates() {
        assertEquals(20260923, ClusterGatewayMain.parseBusinessDate("2026-09-23"));
        assertEquals(20260923, ClusterGatewayMain.parseBusinessDate("20260923"));
        assertEquals(-1, ClusterGatewayMain.parseBusinessDate("2026-02-30"));
        assertEquals(-1, ClusterGatewayMain.parseBusinessDate("tomorrow"));
    }

    // ===== FIX mapping (FR-OT04, SC-OT48) ======================================================

    private static NewOrderSingle nos(final char ordType) {
        final NewOrderSingle m = new NewOrderSingle(new ClOrdID("x"), new Side(Side.BUY),
            new TransactTime(), new OrdType(ordType));
        m.set(new OrderQty(10));
        return m;
    }

    private static Object map(final Message m) {
        return FixGatewayAcceptor.mapOrder(m, InputEvent.SIDE_BUY, 10, false);
    }

    @Test
    void sc48_fixMapsAllSevenTypes() {
        final NewOrderSingle market = nos('1');
        assertEquals(0L, map(market), "F1: a Price-less market order maps (untyped, as REST limitPrice 0)");

        final NewOrderSingle limit = nos('2');
        limit.set(new Price(101.5));
        assertEquals(101_500_000L, map(limit), "plain limit stays untyped: byte-identical legacy flow");

        final NewOrderSingle ioc = nos('2');
        ioc.set(new Price(101.5));
        ioc.setChar(TIF_TAG, '3');
        assertEquals(OrderTypes.IOC, ((OrderSubmitter.TypedOrder) map(ioc)).tif());

        final NewOrderSingle ice = nos('2');
        ice.set(new Price(100));
        ice.setDouble(111, 4);
        final OrderSubmitter.TypedOrder iceberg = (OrderSubmitter.TypedOrder) map(ice);
        assertEquals(OrderTypes.ICEBERG, iceberg.orderType());
        assertEquals(4, iceberg.displayQty());

        final NewOrderSingle stop = nos('3');
        stop.setDouble(99, 101);
        stop.setChar(TIF_TAG, '0');
        final OrderSubmitter.TypedOrder s = (OrderSubmitter.TypedOrder) map(stop);
        assertEquals(OrderTypes.STOP, s.orderType());
        assertEquals(OrderTypes.DAY, s.tif());

        final NewOrderSingle stopLimit = nos('4');
        stopLimit.setDouble(99, 101);
        stopLimit.set(new Price(102));
        assertEquals(OrderTypes.STOP_LIMIT, ((OrderSubmitter.TypedOrder) map(stopLimit)).orderType());

        final NewOrderSingle primary = nos('P');
        primary.setString(18, "R");
        primary.set(new Price(101));
        primary.setInt(836, 2);
        primary.setDouble(211, -1);
        primary.setInt(840, 1);
        final OrderSubmitter.TypedOrder pg = (OrderSubmitter.TypedOrder) map(primary);
        assertEquals(OrderTypes.PEGGED, pg.orderType());
        assertEquals(OrderTypes.PEG_PRIMARY, pg.pegRef());
        assertEquals(-1, pg.pegOffset());
        assertEquals(101 * PX, pg.limitPx(), "Price is the cap");

        final NewOrderSingle mid = nos('P');
        mid.setString(18, "M");
        mid.set(new Price(101));
        assertEquals(OrderTypes.PEG_MIDPOINT, ((OrderSubmitter.TypedOrder) map(mid)).pegRef());

        final NewOrderSingle trail = nos('P');
        trail.setString(18, "a");
        trail.setInt(836, 1);
        trail.setDouble(211, 150);
        final OrderSubmitter.TypedOrder tr = (OrderSubmitter.TypedOrder) map(trail);
        assertEquals(OrderTypes.TRAILING_STOP, tr.orderType());
        assertEquals(OrderTypes.TRAIL_BPS, tr.trailMode());
        assertEquals(150, tr.trailValue());

        final NewOrderSingle trailAmount = nos('P');
        trailAmount.setString(18, "a");
        trailAmount.setDouble(211, -2.5);
        assertEquals(2_500_000L, ((OrderSubmitter.TypedOrder) map(trailAmount)).trailValue());
    }

    @Test
    void sc48_fixRefusesWhatThisVenueCannotHonour() {
        final Object[][] cases = {
            { "P", "market peg (ExecInst P) not supported: pegs are passive (local book only)" },
            { "R840=2", "PegScope(840) must be 1 (local): this venue has no national or global reference" },
            { "R835=1", "PegMoveType(835) fixed not supported" },
            { "R838=1", "PegRoundDirection(838) must be 2 (more passive)" },
            { "R837=0", "PegLimitType(837) not supported" },
            { "", "OrdType P requires exactly one ExecInst(18) of R, M or a" },
            { "R 1", "OrdType P requires exactly one ExecInst(18) of R, M or a" },
            { "R836=0", "peg offset must be in ticks: PegOffsetType(836)=2" },
        };
        for (final Object[] c : cases) {
            final NewOrderSingle m = nos('P');
            m.set(new Price(101));
            final String spec = (String) c[0];
            final int eq = spec.indexOf('=');
            final String inst = eq < 0 ? spec : spec.substring(0, eq - 3);
            if (!inst.isEmpty()) {
                m.setString(18, inst);
            }
            if (eq >= 0) {
                m.setInt(Integer.parseInt(spec.substring(eq - 3, eq)), Integer.parseInt(spec.substring(eq + 1)));
                if (spec.startsWith("R836")) {
                    m.setDouble(211, 1);
                }
            }
            assertEquals(c[1], map(m), spec);
        }
        final NewOrderSingle marketWithPrice = nos('1');
        marketWithPrice.set(new Price(1));
        assertEquals("Price(44) not allowed on a market order", map(marketWithPrice));
        final NewOrderSingle gtd = nos('2');
        gtd.set(new Price(1));
        gtd.setChar(TIF_TAG, '6');
        assertEquals("TimeInForce(59) not supported", map(gtd));
        final NewOrderSingle moc = nos('5');
        assertEquals("OrdType(40) not supported", map(moc));
    }

    /** Review I3 on FIX: the same exactness and presence rules as REST, typed orders only. */
    @Test
    void i3_fixMapperIsExactAboutPresenceAndPrecision() {
        final NewOrderSingle ice = nos('2');
        ice.set(new Price(100));
        ice.setString(111, "3.00000000000000001");
        assertEquals("MaxFloor(111) must be a whole number", map(ice));

        final NewOrderSingle peg = nos('P');
        peg.setString(18, "R");
        peg.set(new Price(101));
        peg.setInt(836, 2);
        peg.setString(211, "-1.5");
        assertEquals("PegOffsetValue(211) must be a whole number of ticks", map(peg));

        final NewOrderSingle bps = nos('P');
        bps.setString(18, "a");
        bps.setInt(836, 1);
        bps.setString(211, "1.00000000000000001");
        assertEquals("PegOffsetValue(211) must be a whole number of basis points", map(bps));

        final NewOrderSingle amount = nos('P');
        amount.setString(18, "a");
        amount.setString(211, "0.0000001");
        assertEquals("PegOffsetValue(211) allows at most 6 decimal places", map(amount));

        final NewOrderSingle price = nos('2');
        price.setString(44, "101.000000000000001");
        price.setChar(TIF_TAG, '1');
        assertEquals("Price(44) and StopPx(99) allow at most 6 decimal places", map(price));

        final NewOrderSingle qty = nos('2');
        qty.set(new Price(101));
        qty.setChar(TIF_TAG, '1');
        qty.setString(38, "10.00000000000000001");
        assertEquals("OrderQty(38) must be a whole number", map(qty));

        // Fields the type does not use are refused even at zero, where they used to be ignored.
        final NewOrderSingle stopFloor = nos('3');
        stopFloor.setDouble(99, 101);
        stopFloor.setString(111, "0");
        assertEquals("field not allowed for this orderType", map(stopFloor));
        final NewOrderSingle limitPeg = nos('2');
        limitPeg.set(new Price(101));
        limitPeg.setChar(TIF_TAG, '1');
        limitPeg.setString(211, "0");
        assertEquals("field not allowed for this orderType", map(limitPeg));
        final NewOrderSingle stopZero = nos('3');
        stopZero.setString(99, "0");
        assertEquals("price must be positive and within range", map(stopZero));

        // Exact values survive whole.
        final NewOrderSingle exact = nos('2');
        exact.setString(44, "101.123456");
        exact.setChar(TIF_TAG, '1');
        assertEquals(101_123_456L, ((OrderSubmitter.TypedOrder) map(exact)).limitPx());
        // Untyped legacy flow keeps today's conversion byte for byte (NFR-OT05).
        final NewOrderSingle legacy = nos('2');
        legacy.setString(44, "101.1234567");
        assertEquals(Math.round(101.1234567 * 1_000_000d), map(legacy));
    }

    // ===== F1 end to end: every NewOrderSingle is answered ====================================

    private FixGatewayAcceptor acceptor;
    private SocketInitiator initiator;

    @AfterEach
    void tearDown() {
        if (initiator != null) {
            initiator.stop();
        }
        if (acceptor != null) {
            acceptor.stop();
        }
    }

    private static final class Recording implements OrderSubmitter {
        final AtomicInteger next = new AtomicInteger(1);
        final List<Object> calls = new CopyOnWriteArrayList<>();

        @Override
        public ExecResult submitOrder(final String clOrdId, final int accountId, final String ticker,
                                      final char side, final int qty, final long limitPxTicks) {
            calls.add(limitPxTicks);
            return new ExecResult(true, next.getAndIncrement(), OutputEvent.KIND_ORDER_ACCEPTED);
        }

        @Override
        public ExecResult submitTypedOrder(final String clOrdId, final int accountId, final String ticker,
                                           final char side, final int qty, final TypedOrder typed) {
            calls.add(typed);
            if (typed.tif() == OrderTypes.FOK) {
                return new ExecResult(true, next.getAndIncrement(), OutputEvent.KIND_ORDER_CANCELED,
                    (byte) finos.traderx.ordermatcher.risk.RiskReason.FOK_UNFILLABLE.ordinal());
            }
            return new ExecResult(true, next.getAndIncrement(), OutputEvent.KIND_ORDER_ACCEPTED);
        }
    }

    private static final class Client implements Application {
        final CountDownLatch loggedOn = new CountDownLatch(1);
        final List<ExecutionReport> reports = new CopyOnWriteArrayList<>();
        volatile SessionID sessionId;
        @Override public void onCreate(final SessionID s) { sessionId = s; }
        @Override public void onLogon(final SessionID s) { sessionId = s; loggedOn.countDown(); }
        @Override public void onLogout(final SessionID s) { }
        @Override public void toAdmin(final Message m, final SessionID s) { }
        @Override public void fromAdmin(final Message m, final SessionID s) { }
        @Override public void toApp(final Message m, final SessionID s) { }
        @Override public void fromApp(final Message m, final SessionID s) {
            if (m instanceof ExecutionReport r) {
                reports.add(r);
            }
        }
    }

    private ExecutionReport await(final Client c, final String clOrdId) throws Exception {
        final long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (final ExecutionReport r : c.reports) {
                if (clOrdId.equals(r.getString(ClOrdID.FIELD))) {
                    return r;
                }
            }
            Thread.sleep(20);
        }
        return null;
    }

    @Test
    void sc04_f1_everyNewOrderSingleIsAnsweredOverARealSession() throws Exception {
        final int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        final Recording submitter = new Recording();
        acceptor = new FixGatewayAcceptor(submitter, null, port, "TRADERX", 11, List.of("CLIENT1"));
        acceptor.start();
        final Client client = new Client();
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
        final SessionID sid = new SessionID("FIX.4.4", "CLIENT1", "TRADERX");
        settings.setString(sid, "BeginString", "FIX.4.4");
        initiator = new SocketInitiator(client, new MemoryStoreFactory(), settings,
            new ScreenLogFactory(false, false, false), new DefaultMessageFactory());
        initiator.start();
        assertTrue(client.loggedOn.await(20, TimeUnit.SECONDS));

        final NewOrderSingle market = order("m1", '1');
        Session.sendToTarget(market, client.sessionId);
        final ExecutionReport m1 = await(client, "m1");
        assertNotNull(m1, "F1: the Price-less market order is answered");
        assertEquals(ExecType.NEW, m1.getChar(ExecType.FIELD));
        assertEquals(0L, submitter.calls.get(0), "sequenced as an untyped market order");

        final NewOrderSingle marketPeg = order("p1", 'P');
        marketPeg.setString(18, "P");
        marketPeg.set(new Price(101));
        Session.sendToTarget(marketPeg, client.sessionId);
        final ExecutionReport p1 = await(client, "p1");
        assertNotNull(p1, "a refusal is an ExecutionReport, never silence");
        assertEquals(OrdStatus.REJECTED, p1.getChar(OrdStatus.FIELD));
        assertTrue(p1.getString(Text.FIELD).contains("market peg"));
        assertEquals(1, submitter.calls.size(), "nothing was sequenced for the refusal");

        final NewOrderSingle fok = order("f1", '2');
        fok.set(new Price(101));
        fok.setChar(59, '4');
        Session.sendToTarget(fok, client.sessionId);
        final ExecutionReport f1 = await(client, "f1");
        assertNotNull(f1);
        assertEquals(OrdStatus.CANCELED, f1.getChar(OrdStatus.FIELD), "an unfillable FOK is a cancel");
        assertEquals("FOK_UNFILLABLE", f1.getString(Text.FIELD));

        final NewOrderSingle fractional = order("i1", '2');
        fractional.set(new Price(101));
        fractional.setString(111, "3.8");
        Session.sendToTarget(fractional, client.sessionId);
        final ExecutionReport i1 = await(client, "i1");
        assertNotNull(i1);
        assertEquals(OrdStatus.REJECTED, i1.getChar(OrdStatus.FIELD));
        assertEquals("MaxFloor(111) must be a whole number", i1.getString(Text.FIELD));
        assertEquals(2, submitter.calls.size(), "review I3: nothing was sequenced for it");
    }

    private static NewOrderSingle order(final String clOrdId, final char ordType) {
        final NewOrderSingle o = new NewOrderSingle(new ClOrdID(clOrdId), new Side(Side.BUY),
            new TransactTime(), new OrdType(ordType));
        o.set(new Symbol("IBM"));
        o.set(new OrderQty(10));
        o.set(new Account("11"));
        o.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        return o;
    }
}
