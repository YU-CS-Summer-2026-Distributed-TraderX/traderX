package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.Account;
import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Alex's request, TraderX half: "a test of at least VaR and ES working end to end on each instrument."
 *
 * <p>Every instrument class TraderX books is traded through the REAL ingress of the REAL gateway
 * (a child-JVM {@link ClusterGatewayMain}) into three real in-process members, the venue is closed,
 * the sequenced EOD marker lands at N, the cut is rendered on every member, and the production
 * exporters turn the leader's cut into the write-once fixture (positions, contracts, ready receipt)
 * that {@code eod-risk-bundles} packages:
 * <ul>
 *   <li>listed equity, REST {@code POST /orders} (JWT-bound account, S1);</li>
 *   <li>listed equity option (YU14, OCC symbol), FIX 4.4 {@code NewOrderSingle} (session credentials, S3);</li>
 *   <li>Treasury bill (YU16), the binary order-entry fast path (numeric security id from {@code /resolve});</li>
 *   <li>OTC rates swap (YU17), REST {@code POST /swaps}.</li>
 * </ul>
 * The risk-engine half is {@code risk-portfolio-tools/var_es_e2e.py}, driven by
 * {@code scripts/proofs/yu18-var-es-e2e.sh}; this test writes its artifacts to
 * {@code TRADERX_VAR_ES_E2E_OUTPUT} when set (a new, private directory outside Git), else to a temp dir.
 *
 * <p>Negative control, on the real cut: a counterparties table missing one trading account makes the
 * extract REFUSE (no fixture, not a fixture with a blank), which is the "counterparties CSV must cover
 * every trading account" rule the cloud run tripped over. The missing-mark refusal on the same renderer
 * is {@code RiskExtractTest.anUnmarkableRowRefusesToEmit}; the engine-side missing-market refusal is the
 * Python half's control.
 *
 * <p>Scope, stated plainly: the venue close is the sequenced {@code /session CLOSED} command and the EOD
 * cut is the sequenced marker; the YU06 price-publishing chain (eod-price-db, trade-processor,
 * position-service) is not in this JVM, so marks are the cut's own last trade at N
 * ({@code CLUSTER_LAST_TRADE_AT_N}), exactly what the hxb cloud run produced. The kind proof
 * {@code yu15-risk-extract.sh} covers the published-close path.
 */
@Timeout(240)
class VarEsEndToEndTest {
    private static final int PORT_BASE = TestPorts.freeBase(3);
    private static final List<String> HOSTS = List.of("localhost", "localhost", "localhost");
    static final int BUYER = 22214;
    static final int SELLER = 42422;
    static final String EQUITY = "AAPL";
    /** OCC: root, yymmdd expiry, C, strike x 1000 (AAPL 2026-12-18 240 call). */
    static final String OPTION = "AAPL261218C00240000";
    static final String BOND = "UST-BILL-20270630";
    static final String SWAP_CONVENTIONS = "USD-SOFR-1Y-ACT360";
    /** A second swap in the one TraderX convention whose day count Alex's engine implements
     *  (ACT/365F is Actual/365 Fixed, which ORE spells ACT/365); its notional needs a sequenced FX rate. */
    static final String GBP_SWAP_CONVENTIONS = "GBP-SONIA-1Y-ACT365F";
    static final LocalDate SESSION_DATE = LocalDate.of(2026, 10, 26);
    static final int PRICE_VERSION = 1;
    private static final String FIX_PASSWORD = "e2e-fix-password";

    @TempDir Path root;
    private final List<ClusteredMediaDriver> drivers = new ArrayList<>();
    private final List<ClusteredServiceContainer> containers = new ArrayList<>();
    private final List<MatchingEngineClusteredService> services = new ArrayList<>();
    private MediaDriver clientDriver;
    private AeronCluster markerClient;
    private Process gateway;
    private final StringBuilder gatewayOut = new StringBuilder();
    private SocketInitiator fix;
    private final HttpClient http = HttpClient.newHttpClient();
    private int httpPort, fixPort, binaryPort, probePort;

    @AfterEach
    void close() {
        if (fix != null) {
            fix.stop(true);
        }
        if (gateway != null) {
            gateway.destroy();
            try {
                if (!gateway.waitFor(10, TimeUnit.SECONDS)) {
                    gateway.destroyForcibly();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        CloseHelper.quietCloseAll(markerClient, clientDriver);
        containers.forEach(CloseHelper::quietClose);
        drivers.forEach(CloseHelper::quietClose);
    }

    @Test
    void everyInstrumentClassReachesAWriteOnceFixtureThroughTheRealIngress() throws Exception {
        launchMembers();
        launchGateway();

        // 0. operator seeding (S2): accounts enabled, symbols registered (S4: /seed is the one
        //    registering path) with a reference price each, through the sequenced path.
        seed(BUYER, EQUITY, 150.0);
        seed(SELLER, OPTION, 5.0);
        seed(BUYER, BOND, 0.97);

        // 1. listed equity over REST: a resting sell, then the buy that crosses it.
        final String bearer = DevJwt.bearer(BUYER, SELLER);
        final String sell = post("/orders", bearer, "{\"clientOrderId\":\"e2e-eq-s\",\"accountId\":" + SELLER
            + ",\"ticker\":\"" + EQUITY + "\",\"side\":\"Sell\",\"quantity\":100,\"limitPrice\":150.0}");
        final String buy = post("/orders", bearer, "{\"clientOrderId\":\"e2e-eq-b\",\"accountId\":" + BUYER
            + ",\"ticker\":\"" + EQUITY + "\",\"side\":\"Buy\",\"quantity\":100,\"limitPrice\":150.0}");
        assertTrue(sell.contains("\"orderRef\":") && buy.contains("\"orderRef\":"), sell + " / " + buy);

        // 2. listed option over FIX 4.4 (OCC symbol): one session, two accounts it is bound to (S3).
        //    A wrong Password(554) and a missing one are refused at logon before the good session.
        // ADR-082 S3: no credential reaches the gateway log on any path QuickFIX/J logs a raw message on.
        final String leak = "e2e-leak-" + Long.toHexString(System.nanoTime());
        rawFix("INTRUDER", "A", "98=0", "108=30", "554=" + leak + "-unbound");   // no session exists for this CompID
        rawFix("CLIENT1", "0", "554=" + leak + "-prelogon");                     // a non-logon message before logon
        rawFix("CLIENT1", "A", "98=0", "554=" + leak + "-garbled");              // Logon without HeartBtInt(108)
        for (final String bad : new String[] {leak + "-wrong", null}) {
            final FixClient refused = new FixClient(bad);
            final SocketInitiator attempt = startInitiator(refused);
            try {
                assertFalse(refused.loggedOn.await(4, TimeUnit.SECONDS), "FIX logon with password " + bad);
            } finally {
                attempt.stop(true);
            }
        }
        assertFalse(gatewayLog().contains("FIX logon: CLIENT1"), "the acceptor established a session for a bad credential");
        await(() -> gatewayLog().contains("unknown session"), "the unbound-CompID logon reached the gateway log");
        assertFalse(gatewayLog().contains(leak), "a FIX password reached the gateway log:\n" + gatewayLog());
        assertTrue(gatewayLog().contains("554=********"), "no masked raw message: the leak paths did not run\n" + gatewayLog());
        final FixClient client = new FixClient(FIX_PASSWORD);
        fix = startInitiator(client);
        assertTrue(client.loggedOn.await(30, TimeUnit.SECONDS), "FIX logon with session credentials");
        await(() -> gatewayLog().contains("FIX logon: CLIENT1"), "the gateway logs the good session (control for the check above)");
        sendFixOrder(client.sid, "e2e-opt-s", SELLER, Side.SELL, OPTION, 1, 5.0);
        sendFixOrder(client.sid, "e2e-opt-b", BUYER, Side.BUY, OPTION, 1, 5.0);
        await(() -> client.reports.containsKey("e2e-opt-s") && client.reports.containsKey("e2e-opt-b"),
            "ExecutionReports for both option legs");
        for (final String id : List.of("e2e-opt-s", "e2e-opt-b")) {
            assertNotEquals(OrdStatus.REJECTED, (char) client.reports.get(id), "FIX order " + id + " was rejected");
        }

        // 3. Treasury bill over the binary fast path: numeric security id, USD face, fraction of par.
        final int bondId = Integer.parseInt(field(post("/resolve", null, "{\"ticker\":\"" + BOND + "\"}"), "securityId"));
        try (Socket socket = new Socket("127.0.0.1", binaryPort)) {
            assertEquals(BinaryGatewayAcceptor.STATUS_ACCEPTED, binaryNew(socket, (byte) 1, SELLER, bondId, 100_000, 970_000L, 0xE2E1L),
                "binary SELL of the bill accepted");
            assertEquals(BinaryGatewayAcceptor.STATUS_ACCEPTED, binaryNew(socket, (byte) 0, BUYER, bondId, 100_000, 970_000L, 0xE2E2L),
                "binary BUY of the bill accepted");
        }

        // 4. OTC swap over REST: pay fixed 4.2% on 10mm, sequenced through consensus like every command.
        final String swap = post("/swaps", bearer, "{\"clientOrderId\":\"e2e-swap\",\"accountId\":" + BUYER
            + ",\"payReceive\":\"Pay\",\"notional\":10000000,\"fixedRate\":0.042,\"effectiveDate\":\"2026-10-28\","
            + "\"maturityDate\":\"2031-10-28\",\"conventions\":\"" + SWAP_CONVENTIONS + "\"}");
        assertTrue(swap.contains("\"booked\":true"), swap);
        // ...and a GBP one: the credit gate values a non-USD notional with a SEQUENCED FX rate (yu17-fx-credit).
        post("/risk/control/fxrate", null, "{\"currency\":\"GBP\",\"rate\":1.27}");   // 2xx or post() fails
        final String gbpSwap = post("/swaps", bearer, "{\"clientOrderId\":\"e2e-swap-gbp\",\"accountId\":" + SELLER
            + ",\"payReceive\":\"Receive\",\"notional\":5000000,\"fixedRate\":0.045,\"effectiveDate\":\"2026-10-28\","
            + "\"maturityDate\":\"2029-10-28\",\"conventions\":\"" + GBP_SWAP_CONVENTIONS + "\"}");
        assertTrue(gbpSwap.contains("\"booked\":true"), gbpSwap);

        // Every member applied the same business: three crosses (six account legs) and two contracts.
        await(() -> services.stream().allMatch(s -> s.engine().tradeCounter() == 6 && s.contractCount() == 2),
            "all three members booked 6 trade legs and 2 contracts");

        // 5. venue close (ADR-069): a named log position, not a wall-clock claim.
        final String closed = post("/session", null, "{\"phase\":\"CLOSED\"}");
        assertTrue(closed.contains("\"phase\":\"CLOSED\""), closed);

        // 6. the EOD cut: the sequenced marker names N; every member renders the identical cut at it.
        connectMarkerClient();
        offerMarker(1L);
        // seq is published one statement before the sha is hashed; waiting on seq alone read a null sha under load
        // ("one cut hash on every member ==> expected: <null>", 2026-10-26, a loaded Mac; 6/6 green quiet)
        await(() -> services.stream().allMatch(s -> s.lastExtractCutSeq() > 0 && s.lastExtractCutSha() != null), "marker applied on every member");
        final long seq = services.get(0).lastExtractCutSeq();
        final String sha = services.get(0).lastExtractCutSha();
        final List<String> cuts = new ArrayList<>();
        for (final MatchingEngineClusteredService s : services) {
            assertEquals(seq, s.lastExtractCutSeq(), "one sequence N on every member");
            assertEquals(sha, s.lastExtractCutSha(), "one cut hash on every member");
            cuts.add(renderCut(s, seq));
        }
        assertEquals(cuts.get(0), cuts.get(1));
        assertEquals(cuts.get(0), cuts.get(2));
        final String cut = cuts.get(0);
        assertEquals(sha, RiskExtractCut.sha256(cut), "the rendered cut is the one the members hashed");
        offerMarker(2L);   // quiescence witness: N + 1, nothing else moved
        await(() -> services.stream().allMatch(s -> s.lastExtractCutSeq() == seq + 1), "witness marker at N+1");

        // 7. the fixture, with the production exporters: positions (equity, option, bill), contracts (swap).
        final var stamp = new RiskExtractCsv.Stamp(seq, SESSION_DATE, PRICE_VERSION, sha);
        final Map<Integer, RiskExtractCsv.Counterparty> counterparties = Map.of(
            BUYER, new RiskExtractCsv.Counterparty("CP-BUYER", "NS-BUYER", "USD"),
            SELLER, new RiskExtractCsv.Counterparty("CP-SELLER", "NS-SELLER", "USD"));
        final Map<String, RiskExtractCsv.BondStatic> bonds = Map.of(BOND, RiskExtractCsv.BondStatic.treasury("0", "2027-06-30"));
        final String positions = RiskExtractCsv.render(cut, Map.of(), counterparties, bonds, stamp);
        final String contracts = SwapContractCsv.render(cut, counterparties, stamp);
        final List<String> rows = positions.lines().filter(l -> !l.startsWith("#")).skip(1).toList();
        assertEquals(6, rows.size(), "two accounts x three instruments:\n" + positions);
        for (final String type : List.of(",EQUITY,", ",OPTION,", ",TREASURY,")) {
            assertEquals(2, rows.stream().filter(r -> r.contains(type)).count(), type + " rows in\n" + positions);
        }
        assertTrue(rows.stream().allMatch(r -> r.contains(",CLUSTER_LAST_TRADE_AT_N,")), "marks are the cut's last trade at N");
        assertTrue(contracts.contains(",PAY_FIXED,10000000,0.042000,USD-SOFR,"), contracts);
        assertTrue(contracts.contains(",RECEIVE_FIXED,5000000,0.045000,GBP-SONIA,"), contracts);
        assertEquals(2, contracts.lines().filter(l -> !l.startsWith("#")).skip(1).count(), "two swap contracts");

        // Negative control on the real cut: a counterparties table that misses the seller.
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> RiskExtractCsv.render(cut, Map.of(), Map.of(BUYER, counterparties.get(BUYER)), bonds, stamp));
        assertTrue(refused.getMessage().contains("no counterparty mapping"), refused.getMessage());

        // 8. write-once: the ready receipt is published once; a second identical publish is a no-op,
        //    a different payload for the same stamp is refused, a non-quiescent witness is refused.
        final String requested = System.getenv("TRADERX_VAR_ES_E2E_OUTPUT");
        final Path out = requested == null || requested.isBlank() ? root.resolve("out") : Path.of(requested);
        Files.createDirectories(out);
        final Path pos = out.resolve("positions.csv"), otc = out.resolve("contracts.csv");
        Files.writeString(out.resolve("cut.txt"), cut, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        Files.writeString(pos, positions, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        Files.writeString(otc, contracts, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        final String ready = RiskExtractReady.payload(stamp, seq + 1, positions, contracts, pos.toUri().toString(), otc.toUri().toString());
        final Path receipt = RiskExtractReady.publish(out.resolve("receipts"), stamp, ready);
        assertEquals(receipt, RiskExtractReady.publish(out.resolve("receipts"), stamp, ready));
        assertThrows(java.io.IOException.class, () -> RiskExtractReady.publish(out.resolve("receipts"), stamp, ready + " "));
        assertThrows(IllegalArgumentException.class, () -> RiskExtractReady.payload(stamp, seq + 2, positions, contracts,
            pos.toUri().toString(), otc.toUri().toString()));
        System.out.println("VAR-ES-E2E-TRADERX seq=" + seq + " cutSha256=" + sha + " positions=" + rows.size()
            + " contracts=2 sessionDate=" + SESSION_DATE + " receipt=" + receipt);
    }

    // ---- members, gateway, marker client -----------------------------------------------------

    /** ADR-082: an acceptor without FIX_SESSION_AUTH does not start unless the rig opted in (ALLOW_DEV_SECRETS). */
    @Test
    void gatewayRefusesAnUnauthenticatedFixAcceptor() throws Exception {
        final Map<String, String> base = Map.of("FIX_ACCEPTOR_PORT", String.valueOf(freePort()),
            "AUTH_JWT_SECRET", "e2e-not-a-dev-secret-jwt", "RISK_CONTROL_TOKEN", "e2e-not-a-dev-secret-risk",
            "GATEWAY_AERON_DIR", root.resolve("refusal-aeron").toString(),
            "GATEWAY_INGRESS_ENDPOINTS", "0=localhost:" + freePort(),
            "GATEWAY_HTTP_PORT", String.valueOf(freePort()), "GATEWAY_PROBE_PORT", String.valueOf(freePort()));
        final Process refused = startGatewayProcess(base);
        assertTrue(refused.waitFor(60, TimeUnit.SECONDS), "the gateway kept running without FIX_SESSION_AUTH");
        final String out = new String(refused.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertNotEquals(0, refused.exitValue(), out);
        assertTrue(out.contains("FIX_SESSION_AUTH is unset or a development default"), out);

        // Control: the same environment with session credentials gets past the check (it then waits for a cluster).
        final Map<String, String> configured = new java.util.HashMap<>(base);
        configured.put("FIX_SESSION_AUTH", "CLIENT1:" + FIX_PASSWORD + ":" + BUYER);
        configured.put("GATEWAY_AERON_DIR", root.resolve("control-aeron").toString());
        final Process control = startGatewayProcess(configured);
        try {
            assertFalse(control.waitFor(10, TimeUnit.SECONDS), "the configured gateway exited");
        } finally {
            control.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** One raw FIX 4.4 frame to the acceptor (header 49=compId, 56=TRADERX), then whatever it answers until it closes. */
    private void rawFix(final String compId, final String msgType, final String... fields) throws Exception {
        final StringBuilder body = new StringBuilder();
        for (final String f : new String[] {"35=" + msgType, "49=" + compId, "56=TRADERX", "34=1",
                "52=" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS")
                    .format(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))}) {
            body.append(f).append('\u0001');
        }
        for (final String f : fields) {
            body.append(f).append('\u0001');
        }
        final String head = "8=FIX.4.4\u00019=" + body.length() + "\u0001";
        int sum = 0;
        for (final char c : (head + body).toCharArray()) {
            sum += c;
        }
        final String frame = head + body + "10=" + String.format("%03d", sum % 256) + "\u0001";
        try (Socket socket = new Socket("localhost", fixPort)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(frame.getBytes(StandardCharsets.US_ASCII));
            try {
                while (socket.getInputStream().read() >= 0) { }
            } catch (final java.net.SocketTimeoutException stillOpen) { }
        }
    }

    private static Process startGatewayProcess(final Map<String, String> env) throws Exception {
        final ProcessBuilder pb = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "-Xmx256m", "-cp", System.getProperty("java.class.path"), ClusterGatewayMain.class.getName())
            .redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> k.startsWith("FIX_") || k.equals("ALLOW_DEV_SECRETS"));
        pb.environment().putAll(env);
        return pb.start();
    }

    private void launchMembers() {
        for (int id = 0; id < 3; id++) {
            final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
            services.add(service);
            final var contexts = ClusterNodeConfig.contexts(id, HOSTS, PORT_BASE, root.resolve("aeron-" + id).toString(),
                root.resolve("node-" + id).toFile(), service, true);
            drivers.add(ClusteredMediaDriver.launch(contexts.mediaDriver(), contexts.archive(), contexts.consensusModule()));
            containers.add(ClusteredServiceContainer.launch(contexts.container()));
        }
        await(() -> services.stream().anyMatch(s -> s.role() == Cluster.Role.LEADER), "a leader");
    }

    /** The real gateway, as the manifests run it: its own JVM, configured by environment. */
    private void launchGateway() throws Exception {
        httpPort = freePort();
        probePort = freePort();
        fixPort = freePort();
        binaryPort = freePort();
        final ProcessBuilder pb = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "-Xmx512m", "-cp", System.getProperty("java.class.path"), ClusterGatewayMain.class.getName())
            .redirectErrorStream(true);
        final Map<String, String> env = pb.environment();
        env.put("GATEWAY_INGRESS_ENDPOINTS", ClusterNodeConfig.ingressEndpoints(HOSTS, PORT_BASE));
        env.put("GATEWAY_AERON_DIR", root.resolve("gateway-aeron").toString());
        env.put("GATEWAY_HTTP_PORT", String.valueOf(httpPort));
        env.put("GATEWAY_PROBE_PORT", String.valueOf(probePort));
        env.put("GATEWAY_EGRESS_HOST", "localhost");
        env.put("FIX_ACCEPTOR_PORT", String.valueOf(fixPort));
        env.put("FIX_SESSION_COMPIDS", "CLIENT1");
        env.put("FIX_SESSION_AUTH", "CLIENT1:" + FIX_PASSWORD + ":" + BUYER + "|" + SELLER);
        env.put("FIX_DEFAULT_ACCOUNT", String.valueOf(BUYER));
        env.put("BINARY_ACCEPTOR_PORT", String.valueOf(binaryPort));
        env.put("ALLOW_DEV_SECRETS", "true");
        env.put("AUTH_JWT_SECRET", DevJwt.DEV_SECRET);
        gateway = pb.start();
        final Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(gateway.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    synchronized (gatewayOut) { gatewayOut.append(line).append('\n'); }
                }
            } catch (final java.io.IOException ignored) { }
        });
        reader.setDaemon(true);
        reader.start();
        await(() -> {
            if (!gateway.isAlive()) {
                fail("gateway died:\n" + gatewayLog());
            }
            return gatewayLog().contains("GATEWAY up");
        }, "gateway up");
        await(() -> get("/ready").contains("200"), "gateway ready");
    }

    private void connectMarkerClient() {
        clientDriver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(root.resolve("marker-aeron").toString())
            .threadingMode(ThreadingMode.SHARED).termBufferSparseFile(true).dirDeleteOnStart(true));
        markerClient = AeronCluster.connect(new AeronCluster.Context().aeronDirectoryName(clientDriver.aeronDirectoryName())
            .ingressChannel("aeron:udp?term-length=64k").ingressEndpoints(ClusterNodeConfig.ingressEndpoints(HOSTS, PORT_BASE))
            .egressChannel("aeron:udp?endpoint=localhost:0")
            .egressListener((session, time, data, offset, length, header) -> { }));
    }

    /** The producer's marker ({@link RiskExtractMain}): session date and price version, sequenced. */
    private void offerMarker(final long requestId) {
        final AeronReplicationCodec codec = new AeronReplicationCodec();
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[AeronReplicationCodec.RISK_EXTRACT_BYTES]);
        codec.encodeRiskExtract(buffer, 0, requestId, SESSION_DATE.toEpochDay(), PRICE_VERSION);
        await(() -> {
            markerClient.pollEgress();
            return markerClient.offer(buffer, 0, AeronReplicationCodec.RISK_EXTRACT_BYTES) > 0;
        }, "marker offered");
    }

    private static String renderCut(final MatchingEngineClusteredService s, final long seq) {
        final String[] tickers = new String[MatchingEngineClusteredService.MAX_SECURITIES];
        for (final String ticker : List.of(EQUITY, OPTION, BOND)) {
            tickers[s.symbolIdFor(ticker)] = ticker;
        }
        return RiskExtractCut.render(seq, SESSION_DATE.toEpochDay(), PRICE_VERSION, s.engine().positionTuples(),
            s.engine().priceTuples(), tickers, id -> s.risk().contractMultiplier(id), s.contractTuples());
    }

    // ---- ingress helpers ---------------------------------------------------------------------

    private void seed(final int account, final String tickers, final double price) throws Exception {
        final String r = post("/seed", null, "{\"accountId\":" + account + ",\"tickers\":\"" + tickers + "\",\"price\":" + price + "}");
        assertTrue(r.contains("\"seeded\":true"), "seed " + tickers + ": " + r);
    }

    private String post(final String path, final String bearer, final String json) throws Exception {
        final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + path))
            .timeout(java.time.Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            // S2 operator credentials (the dev token, allowed by ALLOW_DEV_SECRETS=true); harmless on order routes.
            .header("X-Risk-Control-Token", "dev-risk-control").header("X-Risk-Operator", "var-es-e2e")
            .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) {
            b.header("Authorization", bearer);
        }
        final HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(r.statusCode() / 100 == 2, path + " -> " + r.statusCode() + " " + r.body());
        return r.body();
    }

    private String get(final String path) {
        try {
            final HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + probePort + path))
                .timeout(java.time.Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() + " " + r.body();
        } catch (final Exception e) {
            return "0 " + e;
        }
    }

    private static String field(final String json, final String name) {
        final var m = java.util.regex.Pattern.compile("\"" + name + "\":(\\d+)").matcher(json);
        assertTrue(m.find(), name + " in " + json);
        return m.group(1);
    }

    /** One binary NEW frame ({@link BinaryGatewayAcceptor} wire), returning the ack's status byte. */
    private static byte binaryNew(final Socket socket, final byte side, final int account, final int security,
                                  final int qty, final long limitPx, final long clOrdId) throws Exception {
        final UnsafeBuffer frame = new UnsafeBuffer(new byte[2 + 32]);
        frame.putShort(0, (short) 32, ByteOrder.LITTLE_ENDIAN);
        frame.putByte(2, (byte) BinaryGatewayAcceptor.MSG_NEW);
        frame.putByte(3, side);
        frame.putInt(2 + 4, account, ByteOrder.LITTLE_ENDIAN);
        frame.putInt(2 + 8, security, ByteOrder.LITTLE_ENDIAN);
        frame.putInt(2 + 12, qty, ByteOrder.LITTLE_ENDIAN);
        frame.putLong(2 + 16, limitPx, ByteOrder.LITTLE_ENDIAN);
        frame.putLong(2 + 24, clOrdId, ByteOrder.LITTLE_ENDIAN);
        final OutputStream out = socket.getOutputStream();
        out.write(frame.byteArray(), 0, 34);
        out.flush();
        final DataInputStream in = new DataInputStream(socket.getInputStream());
        final byte[] len = new byte[2];
        in.readFully(len);
        final int ackLen = (len[0] & 0xFF) | ((len[1] & 0xFF) << 8);
        final byte[] ack = new byte[ackLen];
        in.readFully(ack);
        assertEquals(BinaryGatewayAcceptor.ACK_TYPE, ack[0] & 0xFF, "ack frame");
        final UnsafeBuffer a = new UnsafeBuffer(ack);
        assertEquals(clOrdId, a.getLong(8, ByteOrder.LITTLE_ENDIAN), "ack echoes the client's clOrdId");
        return ack[1];
    }

    private SocketInitiator startInitiator(final FixClient app) throws Exception {
        final SessionSettings settings = new SessionSettings();
        final Properties d = new Properties();
        d.setProperty("ConnectionType", "initiator");
        d.setProperty("SocketConnectHost", "127.0.0.1");
        d.setProperty("SocketConnectPort", String.valueOf(fixPort));
        d.setProperty("StartTime", "00:00:00");
        d.setProperty("EndTime", "00:00:00");
        d.setProperty("HeartBtInt", "5");
        d.setProperty("ReconnectInterval", "1");
        d.setProperty("PersistMessages", "N");
        settings.set(d);
        settings.setString(app.sid, "BeginString", "FIX.4.4");
        final SocketInitiator init = new SocketInitiator(app, new MemoryStoreFactory(), settings,
            new ScreenLogFactory(false, false, false), new DefaultMessageFactory());
        init.start();
        return init;
    }

    private static void sendFixOrder(final SessionID sid, final String clOrdId, final int account, final char side,
                                     final String ticker, final int qty, final double px) throws Exception {
        final NewOrderSingle order = new NewOrderSingle(new ClOrdID(clOrdId), new Side(side), new TransactTime(),
            new OrdType(OrdType.LIMIT));
        order.set(new Symbol(ticker));
        order.set(new Account(String.valueOf(account)));
        order.set(new OrderQty(qty));
        order.set(new Price(px));
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
        Session.sendToTarget(order, sid);
    }

    /** Counterparty side of the FIX session: logs on with the password, collects ExecutionReports by ClOrdID. */
    private static final class FixClient implements Application {
        final SessionID sid = new SessionID("FIX.4.4", "CLIENT1", "TRADERX");
        final CountDownLatch loggedOn = new CountDownLatch(1);
        final Map<String, Character> reports = new ConcurrentHashMap<>();
        private final String password;   // null = Logon without Password(554)

        FixClient(final String password) {
            this.password = password;
        }

        @Override public void onCreate(final SessionID sessionId) { }
        @Override public void onLogon(final SessionID sessionId) { loggedOn.countDown(); }
        @Override public void onLogout(final SessionID sessionId) { }
        @Override public void toAdmin(final Message message, final SessionID sessionId) {
            try {
                if ("A".equals(message.getHeader().getString(35)) && password != null) {
                    message.setString(quickfix.field.Password.FIELD, password);
                }
            } catch (final quickfix.FieldNotFound ignored) { }
        }
        @Override public void fromAdmin(final Message message, final SessionID sessionId) { }
        @Override public void toApp(final Message message, final SessionID sessionId) { }
        @Override public void fromApp(final Message message, final SessionID sessionId) throws quickfix.FieldNotFound {
            if ("8".equals(message.getHeader().getString(35)) && message.isSetField(ClOrdID.FIELD)) {
                reports.put(message.getString(ClOrdID.FIELD), message.getChar(OrdStatus.FIELD));
            }
        }
    }

    // ---- plumbing ----------------------------------------------------------------------------

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private String gatewayLog() {
        synchronized (gatewayOut) { return gatewayOut.toString(); }
    }

    private void await(final BooleanSupplier condition, final String what) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what + "\n--- gateway log ---\n" + gatewayLog());
            }
            Thread.onSpinWait();
            try { Thread.sleep(5); } catch (final InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
