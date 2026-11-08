package finos.traderx.ordermatcher.cluster;

import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/**
 * RI33 (FR-AAD-01..03): POST /risk/control/account and /seed answer the members' committed decision.
 * Real gateway handlers over HTTP, three disposable members. Before the fix every case below except
 * the valid admission answered 200 {"applied":true}: the malformed body admitted account 0, a refused
 * admission and an unanswered one were both reported applied.
 */
@Timeout(120)
class AccountAdmissionAckTest {
    @TempDir Path root;
    private final List<ClusteredMediaDriver> drivers = new ArrayList<>();
    private final List<ClusteredServiceContainer> containers = new ArrayList<>();
    private final List<MatchingEngineClusteredService> services = new ArrayList<>();
    private final ClusterGatewayMain gateway = new ClusterGatewayMain();
    private final AtomicBoolean forwardAcks = new AtomicBoolean(true);
    private final AtomicBoolean pumping = new AtomicBoolean(true);
    private final HttpClient http = HttpClient.newHttpClient();
    private MediaDriver clientDriver;
    private AeronCluster client;
    private Thread pump;
    private com.sun.net.httpserver.HttpServer server;
    private String base;

    @BeforeEach void cluster() throws Exception {
        var hosts = List.of("localhost", "localhost", "localhost");
        int port = TestPorts.freeBase(3);
        for (int id = 0; id < 3; id++) {
            var service = new MatchingEngineClusteredService();
            services.add(service);
            var contexts = ClusterNodeConfig.contexts(id, hosts, port, root.resolve("aeron-" + id).toString(),
                root.resolve("node-" + id).toFile(), service, true);
            drivers.add(ClusteredMediaDriver.launch(contexts.mediaDriver(), contexts.archive(), contexts.consensusModule()));
            containers.add(ClusteredServiceContainer.launch(contexts.container()));
        }
        await(() -> services.stream().anyMatch(s -> s.role() == Cluster.Role.LEADER));
        clientDriver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(root.resolve("client").toString())
            .threadingMode(ThreadingMode.SHARED).termBufferSparseFile(true).dirDeleteOnStart(true));
        Method onEgress = ClusterGatewayMain.class.getDeclaredMethod("onEgress", long.class, long.class,
            DirectBuffer.class, int.class, int.class, io.aeron.logbuffer.Header.class);
        onEgress.setAccessible(true);
        client = AeronCluster.connect(new AeronCluster.Context().aeronDirectoryName(clientDriver.aeronDirectoryName())
            .ingressChannel("aeron:udp?term-length=64k").ingressEndpoints(ClusterNodeConfig.ingressEndpoints(hosts, port))
            .egressChannel("aeron:udp?endpoint=localhost:0")
            .egressListener((session, time, data, offset, length, header) -> {
                if (!forwardAcks.get()) return;   // the decision commits; the gateway never hears it
                try { onEgress.invoke(gateway, session, time, data, offset, length, header); }
                catch (Exception ex) { throw new IllegalStateException(ex); }
            }));
        field("client").set(gateway, client);
        @SuppressWarnings("unchecked")
        Queue<FutureTask<?>> tasks = (Queue<FutureTask<?>>) field("tasks").get(gateway);
        // The owner loop's job: it alone touches the cluster client.
        pump = new Thread(() -> {
            long keepAlive = 0;
            while (pumping.get()) {
                FutureTask<?> task;
                while ((task = tasks.poll()) != null) task.run();
                client.pollEgress();
                if (System.currentTimeMillis() - keepAlive > 500) { client.sendKeepAlive(); keepAlive = System.currentTimeMillis(); }
                Thread.yield();
            }
        }, "test-owner");
        pump.start();
        server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        route("/risk/control", "handleRiskControl");
        route("/seed", "handleSeed");
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void close() throws Exception {
        if (server != null) server.stop(0);
        pumping.set(false);
        if (pump != null) pump.join(5_000);
        CloseHelper.quietCloseAll(client, clientDriver);
        for (var container : containers) CloseHelper.quietClose(container);
        for (var driver : drivers) CloseHelper.quietClose(driver);
    }

    @Test void malformedBodyIsRefusedAndAdmitsNobody() throws Exception {
        // The RI33 body: bash 3.2 brace-expanded {"accountId":42422,"enabled":true} into two words.
        for (String body : List.of("\"accountId\":42422", "\"enabled\":true", "{\"accountId\":\"42422\"}",
                "{\"accountId\":0}", "{\"accountId\":42422,\"enabled\":\"true\"}")) {
            var r = post("/risk/control/account", body);
            assertEquals(400, r.statusCode(), body + " -> " + r.body());
        }
        assertTrue(services.stream().allMatch(s -> s.engine().riskState().accountTuples().isEmpty()),
            "a refused body sequenced an admission");
    }

    @Test void admissionAnswers200OnlyOnceEveryMemberCanHoldIt() throws Exception {
        var r = post("/risk/control/account", "{\"accountId\":42422,\"enabled\":true}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"applied\":true") && r.body().contains("\"sequence\":"), r.body());
        assertTrue(holds(leader(), 42422), "200 before the leader applied it");
        await(() -> services.stream().allMatch(s -> holds(s, 42422)));
    }

    @Test void committedRefusalIs422() throws Exception {
        for (int a = 1; a <= 128; a++) {   // MAX_ACCOUNTS 64 -> 128 backing slots (F7)
            assertEquals(200, post("/risk/control/account", "{\"accountId\":" + a + "}").statusCode(), "account " + a);
        }
        var r = post("/risk/control/account", "{\"accountId\":129,\"enabled\":true}");
        assertEquals(422, r.statusCode(), r.body());
        assertTrue(r.body().contains("ACCOUNT_TABLE_FULL") && r.body().contains("\"applied\":false"), r.body());
        var seed = post("/seed", "{\"accountId\":130,\"tickers\":\"IBM\",\"price\":100}");
        assertEquals(422, seed.statusCode(), "seed reported a refused admission as " + seed.body());
        assertFalse(holds(leader(), 129));
    }

    @Test void noCommittedDecisionIs504NotApplied() throws Exception {
        forwardAcks.set(false);
        var r = post("/risk/control/account", "{\"accountId\":42422,\"enabled\":true}");
        assertEquals(504, r.statusCode(), r.body());
        assertFalse(r.body().contains("\"applied\":true"), r.body());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .header("X-Risk-Control-Token", "dev-risk-control").header("X-Risk-Operator", "ri33-test")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static boolean holds(MatchingEngineClusteredService s, int account) {
        return s.engine().riskState().accountTuples().stream().anyMatch(t -> t[0] == account);
    }

    private MatchingEngineClusteredService leader() {
        return services.stream().filter(s -> s.role() == Cluster.Role.LEADER).findFirst().orElseThrow();
    }

    private void route(String path, String handler) throws Exception {
        Method m = ClusterGatewayMain.class.getDeclaredMethod(handler, com.sun.net.httpserver.HttpExchange.class);
        m.setAccessible(true);
        server.createContext(path, ex -> {
            try { m.invoke(gateway, ex); } catch (Exception e) { throw new IllegalStateException(e); }
        });
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("condition did not complete within 30 seconds");
            Thread.yield();
        }
    }

    private static Field field(String name) throws Exception {
        Field f = ClusterGatewayMain.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
