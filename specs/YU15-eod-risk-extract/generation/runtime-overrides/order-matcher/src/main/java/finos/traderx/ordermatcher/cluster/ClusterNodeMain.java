package finos.traderx.ordermatcher.cluster;

import com.sun.net.httpserver.HttpServer;
import finos.traderx.ordermatcher.lmax.MatchingEngine;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.agrona.CloseHelper;
import org.agrona.concurrent.ShutdownSignalBarrier;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Containerized Aeron Cluster member: Media Driver + Archive + Consensus Module + the
 * matching/risk ClusteredService in one process, wired by {@link ClusterNodeConfig}.
 *
 * Environment:
 *  - {@code CLUSTER_MEMBER_ID}: this member's id, or derived from the trailing ordinal of
 *    {@code HOSTNAME} (StatefulSet pod name) when unset;
 *  - {@code CLUSTER_HOSTNAMES}: comma-separated member hostnames indexed by member id;
 *  - {@code CLUSTER_PORT_BASE} (default 21800), {@code CLUSTER_BASE_DIR} (default /data),
 *    {@code CLUSTER_AERON_DIR} (default /dev/shm/aeron-cluster).
 *
 * Health (stdlib HTTP on {@code HEALTH_PORT}, default 8080): {@code /health} reports member id,
 * role, applied sequence, trades, and snapshot count; {@code /ready} is 200 once the service
 * has started AND this member is caught up to within {@code CLUSTER_READY_MAX_LAG} of its
 * furthest-ahead peer — the gate that makes k8s rolling restarts safe on emptyDir members.
 */
public final class ClusterNodeMain {
    public static void main(final String[] args) throws Exception {
        final int memberId = memberId();
        final List<String> hostnames = Arrays.asList(env("CLUSTER_HOSTNAMES", "localhost").split(","));
        final int portBase = Integer.parseInt(env("CLUSTER_PORT_BASE", "21800"));
        final File baseDir = new File(env("CLUSTER_BASE_DIR", "/data"));
        final String aeronDir = env("CLUSTER_AERON_DIR", "/dev/shm/aeron-cluster");
        final int healthPort = Integer.parseInt(env("HEALTH_PORT", "8080"));

        // The consensus module resolves every member endpoint when it parses the member list;
        // an unresolvable peer at that instant is a terminal error (observed live on kind:
        // Parallel pod start races headless-DNS registration). Wait for the whole roster first,
        // and exit on any cluster termination so the pod restart retries from a clean parse.
        awaitDns(hostnames);

        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        // YU05 recon/regulatory on this tier (see ClusterRecon). Wired BEFORE the container
        // launches so the live forward window also catches the recovery replay of the log tail,
        // which is how a restarted member rebuilds it. Unset capacity leaves the tap null and the
        // apply path byte-for-byte what it was.
        final ClusterRecon recon = reconOrNull(baseDir, aeronDir);
        if (recon != null) {
            service.outputSink(out -> recon.onLiveOutput(out, service.tickerFor(out.securityId)));
        }
        final ClusterNodeConfig.Contexts contexts =
            ClusterNodeConfig.contexts(memberId, hostnames, portBase, aeronDir, baseDir, service, false);
        contexts.consensusModule().terminationHook(() -> {
            System.err.println("Consensus module terminated; exiting for pod restart");
            Runtime.getRuntime().halt(70);
        });

        final ClusteredMediaDriver driver = ClusteredMediaDriver.launch(
            contexts.mediaDriver(), contexts.archive(), contexts.consensusModule());
        final ClusteredServiceContainer container = ClusteredServiceContainer.launch(contexts.container());
        final HttpServer health = healthServer(healthPort, memberId, hostnames, service, recon);
        startSnapshotTrigger(aeronDir, service);
        startElectionPhaseWatcher(aeronDir);

        System.out.println("Cluster node up: memberId=" + memberId + " hostnames=" + hostnames
            + " portBase=" + portBase + " baseDir=" + baseDir);

        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            health.stop(0);
            CloseHelper.quietCloseAll(container, driver);
        }));
        barrier.await();
    }

    private static void awaitDns(final List<String> hostnames) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 180_000;
        for (final String hostname : hostnames) {
            while (true) {
                try {
                    java.net.InetAddress.getByName(hostname);
                    break;
                } catch (final java.net.UnknownHostException e) {
                    if (System.currentTimeMillis() > deadline) {
                        throw new IllegalStateException("member DNS never resolved: " + hostname);
                    }
                    System.out.println("Waiting for member DNS: " + hostname);
                    Thread.sleep(1_000);
                }
            }
        }
    }

    /** Periodic snapshots (default every 60 s; CLUSTER_SNAPSHOT_INTERVAL_MS, 0 = off): the
     *  leader toggles the consensus module's SNAPSHOT control counter — the same mechanism as
     *  `ClusterTool snapshot` — and every member snapshots at the same log position. Bounds
     *  restart replay to the tail since the last snapshot instead of the whole log. Log
     *  segments are NOT purged here (recovery = latest snapshot + tail; purge is a separate
     *  ops action). Only the leader toggles, so followers idle cheaply.
     *  60 s measured as the sweet spot on GKE under a ~46k orders/s flood: each snapshot is a
     *  log-position barrier costing ~8 s of cluster-wide apply stall at this state size (A/B
     *  proven, 2026-07-19), while recovery is pod-restart dominated (~40-66 s) and the tail
     *  replays at ~300k events/s — so shorter intervals buy seconds of recovery at a ~25%
     *  sustained-flood throughput tax. Async/incremental snapshots are the real fix if the
     *  stall ever matters more. */
    private static void startSnapshotTrigger(final String aeronDir,
                                             final MatchingEngineClusteredService service) {
        final long intervalMs = Long.parseLong(env("CLUSTER_SNAPSHOT_INTERVAL_MS", "60000"));
        if (intervalMs <= 0) {
            return;
        }
        final Thread trigger = new Thread(() -> {
            try (io.aeron.Aeron aeron = io.aeron.Aeron.connect(
                    new io.aeron.Aeron.Context().aeronDirectoryName(aeronDir))) {
                while (true) {
                    Thread.sleep(intervalMs);
                    if (service.role() != io.aeron.cluster.service.Cluster.Role.LEADER) {
                        continue;
                    }
                    final org.agrona.concurrent.status.AtomicCounter toggle =
                        io.aeron.cluster.ClusterControl.findControlToggle(aeron.countersReader(), 0);
                    if (toggle != null) {
                        io.aeron.cluster.ClusterControl.ToggleState.SNAPSHOT.toggle(toggle);
                    }
                }
            } catch (final InterruptedException ignore) {
                // shutdown
            } catch (final Exception e) {
                System.err.println("snapshot trigger stopped: " + e);
            }
        }, "snapshot-trigger");
        trigger.setDaemon(true);
        trigger.start();
    }

    /** Phase-0 harness: log every election-state transition with a node-clock timestamp so the
     *  failover budget can be split into detection vs canvass vs ballot vs log-join
     *  (`ELECTION-PHASE state=<S> atMs=<ms>`). ~1 ms poll of the CLUSTER_ELECTION_STATE counter
     *  on a daemon thread — measurement infra, never in the apply path. */
    private static void startElectionPhaseWatcher(final String aeronDir) {
        final Thread watcher = new Thread(() -> {
            try (io.aeron.Aeron aeron = io.aeron.Aeron.connect(
                    new io.aeron.Aeron.Context().aeronDirectoryName(aeronDir))) {
                final org.agrona.concurrent.status.CountersReader counters = aeron.countersReader();
                int counterId = -1;
                long last = -1;
                while (true) {
                    if (counterId < 0) {
                        counterId = io.aeron.cluster.service.ClusterCounters.find(
                            counters, io.aeron.AeronCounters.CLUSTER_ELECTION_STATE_TYPE_ID, 0);
                    } else {
                        final long code = counters.getCounterValue(counterId);
                        if (code != last) {
                            last = code;
                            System.out.println("ELECTION-PHASE state="
                                + io.aeron.cluster.ElectionState.get(code)
                                + " atMs=" + System.currentTimeMillis());
                        }
                    }
                    Thread.sleep(1);
                }
            } catch (final InterruptedException ignore) {
                // shutdown
            } catch (final Exception e) {
                System.err.println("election-phase watcher stopped: " + e);
            }
        }, "election-phase-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static int memberId() {
        final String explicit = System.getenv("CLUSTER_MEMBER_ID");
        if (explicit != null && !explicit.isEmpty()) {
            return Integer.parseInt(explicit);
        }
        final String hostname = env("HOSTNAME", "");
        final int dash = hostname.lastIndexOf('-');
        if (dash < 0) {
            throw new IllegalStateException("set CLUSTER_MEMBER_ID or run with an ordinal HOSTNAME");
        }
        return Integer.parseInt(hostname.substring(dash + 1));
    }

    /** YU05 recon/regulatory surface, or null when {@code RECON_BLOTTER_CAPACITY} is 0/unset — in
     *  which case the member behaves exactly as it did before this existed. */
    private static ClusterRecon reconOrNull(final File baseDir, final String aeronDir) {
        final int capacity = Integer.parseInt(env("RECON_BLOTTER_CAPACITY", "0"));
        if (capacity <= 0) {
            return null;
        }
        return new ClusterRecon(baseDir, aeronDir,
            env("CLUSTER_EPOCH", "1"), // same default OrderNatsPublisher uses, so ids agree
            capacity,
            Integer.parseInt(env("RECON_FULL_HISTORY_MAX", "200000")),
            Integer.parseInt(env("REGULATORY_MAX_RECORDS", "200000")));
    }

    private static HttpServer healthServer(final int port, final int memberId,
                                           final List<String> hostnames,
                                           final MatchingEngineClusteredService service,
                                           final ClusterRecon recon) throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        // Dedicated thread pool: without it the stdlib server runs every handler on ONE dispatcher
        // thread, so a slow /ready (synchronous peer HTTP below) head-of-line-blocks the liveness
        // /health probe until it times out — the flood-time SIGKILL cause. MAX_PRIORITY so the
        // handler wins a CPU slot even while the Aeron duty-cycle threads are saturating the node.
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
            final Thread t = new Thread(r, "health-http");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        }));
        server.createContext("/health", exchange -> {
            final boolean started = service.engine() != null;
            final String body = "{\"memberId\":" + memberId
                + ",\"role\":\"" + service.role() + "\""
                + ",\"started\":" + started
                // Consensus-log position, not the engine's blpSeq: a member restored from a
                // snapshot into an IDLE cluster has applied no engine events since restore, so
                // blpSeq is still -1 while the member is in fact fully caught up. Reporting
                // blpSeq there left the pod permanently NotReady — and an EOD window is exactly
                // when the cluster is idle (YU15, T-RXT07).
                + ",\"applied\":" + (started ? service.appliedSeq() : -1)
                + ",\"engineApplied\":" + (started ? service.engine().blpSeq() : -1)
                // authoritative booked-trade counter (egress acks are best-effort and may drop
                // under load; the bench must read booked/s here, not at the gateway)
                + ",\"trades\":" + (started ? service.engine().tradeCounter() : -1)
                + ",\"snapshots\":" + service.snapshotsTaken() + "}";
            respond(exchange, 200, body);
        });
        // Readiness gates on CATCH-UP, not just service start: a member is ready only when its
        // applied sequence is within CLUSTER_READY_MAX_LAG (default 5000 events, ~150ms of full
        // flood) of the furthest-ahead peer, read from the peers' /health over the headless
        // service. This is what makes `kubectl rollout restart` safe on emptyDir members — the
        // rolling update cannot kill the next member until the restarted one has converged, so
        // the un-snapshotted log tail always lives on a quorum (the tail-loss hazard documented
        // in PROOF-yu12-gke-failover-2026-07-18.md). Unreachable peers are ignored so cold
        // start and quorum-loss states never wedge on their own readiness.
        final long maxLag = Long.parseLong(env("CLUSTER_READY_MAX_LAG", "5000"));
        // The catch-up decision needs synchronous peer HTTP (peerApplied below), which is slow under
        // flood — so compute it on a background sampler every 250ms and have /ready read the cached
        // result. The request path then never blocks on a peer, so the probe stays fast under load.
        final java.util.concurrent.atomic.AtomicReference<String> readyBody =
            new java.util.concurrent.atomic.AtomicReference<>("{\"ready\":false,\"reason\":\"not started\"}");
        final java.util.concurrent.atomic.AtomicBoolean readyFlag =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        final Thread readySampler = new Thread(() -> {
            while (true) {
                try {
                    if (service.engine() == null) {
                        readyFlag.set(false);
                        readyBody.set("{\"ready\":false,\"reason\":\"not started\"}");
                    } else {
                        final long mine = service.appliedSeq();
                        long maxPeer = -1;
                        for (int i = 0; i < hostnames.size(); i++) {
                            if (i == memberId) {
                                continue;
                            }
                            maxPeer = Math.max(maxPeer, peerApplied(hostnames.get(i), port));
                        }
                        final boolean ready = maxPeer < 0 || mine >= maxPeer - maxLag;
                        readyFlag.set(ready);
                        readyBody.set("{\"ready\":" + ready + ",\"applied\":" + mine
                            + ",\"maxPeerApplied\":" + maxPeer + "}");
                    }
                    Thread.sleep(250);
                } catch (final InterruptedException e) {
                    return;
                } catch (final Exception e) {
                    // transient peer/engine hiccup — keep the last decision and retry
                }
            }
        }, "health-ready-sampler");
        readySampler.setDaemon(true);
        readySampler.start();
        server.createContext("/ready", exchange ->
            respond(exchange, readyFlag.get() ? 200 : 503, readyBody.get()));
        // Prometheus scrape surface (Grafana YU12 dashboard): each member exports its own signals
        // labelled by memberId, so Prometheus scraping all three renders per-node role/lag/snapshots.
        server.createContext("/metrics", exchange -> {
            final boolean started = service.engine() != null;
            final int role = service.role() == io.aeron.cluster.service.Cluster.Role.LEADER ? 1 : 0;
            final long applied = started ? service.appliedSeq() : 0;
            final long trades = started ? service.engine().tradeCounter() : 0;
            final String m = "{member=\"" + memberId + "\"} ";
            // YU13: the resting book is replicated state that must be IDENTICAL on every member
            // (and must survive snapshot+failover intact). Expose the engine's order-independent
            // recovery digest so cross-member book equality is directly assertable in live HA
            // proofs instead of inferred from the applied position. Cold path: the digest walks
            // the order index, so scrape it deliberately, not at high frequency.
            final MatchingEngine.RecoveryDigest d = started ? service.engine().recoveryDigest() : null;
            final String body =
                  "# TYPE traderx_cluster_role gauge\ntraderx_cluster_role" + m + role + "\n"
                + "# TYPE traderx_cluster_applied counter\ntraderx_cluster_applied" + m + applied + "\n"
                + "# TYPE traderx_cluster_trades counter\ntraderx_cluster_trades" + m + trades + "\n"
                + "# TYPE traderx_cluster_snapshots counter\ntraderx_cluster_snapshots" + m + service.snapshotsTaken() + "\n"
                + "# TYPE traderx_cluster_up gauge\ntraderx_cluster_up" + m + (started ? 1 : 0) + "\n"
                + "# TYPE traderx_book_open_orders gauge\ntraderx_book_open_orders" + m + (d == null ? 0 : d.openOrders()) + "\n"
                + "# TYPE traderx_book_order_hash gauge\ntraderx_book_order_hash" + m + (d == null ? 0L : d.orderHash()) + "\n"
                + "# TYPE traderx_book_position_hash gauge\ntraderx_book_position_hash" + m + (d == null ? 0L : d.positionHash()) + "\n"
                + "# TYPE traderx_cluster_next_order_ref gauge\ntraderx_cluster_next_order_ref" + m + service.nextOrderRef() + "\n"
                // ADR-057: resting orders cancelled by self-trade prevention. Read HERE and not
                // at the gateway — egress acks are best-effort and drop under flood, so a
                // gateway tally silently undercounts exactly when the number matters.
                + "# TYPE traderx_stp_cancels counter\ntraderx_stp_cancels" + m
                + (started ? service.engine().countSelfTradesPrevented() : 0L) + "\n"
                // OTEL-01: span-sink health. Dropped spans mean telemetry shed load to keep the apply
                // path free — the designed outcome, and the number that tells a supporter their trace
                // sample is thin rather than their system is broken.
                + (service.spanSink() == null ? "" : service.spanSink().metrics());
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        // LATENCY-01 Phase B: leader-clock commit/apply split of the gateway's cluster black box.
        // GET /latency dumps p50/p99/p99.9/max per segment (µs); ?reset=1 zeros them (call after
        // warmup). 503 unless LATENCY_DECOMP=1. Read the LEADER member — only its commit-to-apply is
        // the gateway's round-trip.
        server.createContext("/latency", exchange -> {
            final LeaderApplyLatency lat = service.leaderLatency();
            if (lat == null) {
                respond(exchange, 503, "latency decomposition disabled (set LATENCY_DECOMP=1)\n");
                return;
            }
            final String q = exchange.getRequestURI().getQuery();
            if (q != null && q.contains("reset=1")) {
                lat.reset();
                respond(exchange, 200, "reset\n");
                return;
            }
            respond(exchange, 200, lat.dump());
        });
        reconRoutes(server, service, recon);
        server.start();
        return server;
    }

    /**
     * YU05's reconciliation + regulatory contract (FR-PTC04/05/10/20/21) on the cluster tier. Every
     * route here spans all accounts, so every one requires an {@code admin} JWT (ADR-025) — the
     * same rule the Spring tier's {@code ReconController}/{@code RegulatoryReportController}
     * enforce, and enforced HERE rather than at the gateway because this is where the data is.
     *
     * <p>503 rather than a plausible empty answer when the capability is off: a recon endpoint that
     * returns {@code []} because it was never enabled is indistinguishable from a projection with
     * nothing wrong in it, which is exactly the vacuous pass these proofs exist to refuse.
     */
    private static void reconRoutes(final HttpServer server,
                                    final MatchingEngineClusteredService service,
                                    final ClusterRecon recon) {
        final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();
        final finos.traderx.ordermatcher.auth.JwtAuthenticator jwt =
            new finos.traderx.ordermatcher.auth.JwtAuthenticator(
                env("AUTH_JWT_SECRET", "dev-jwt-shared-secret"));
        final int pageSize = Integer.parseInt(env("RECON_PAGE_SIZE", "1000"));

        final java.util.function.BiFunction<com.sun.net.httpserver.HttpExchange,
            java.util.concurrent.Callable<Object>, Void> guarded = (exchange, body) -> {
                try {
                    if (recon == null) {
                        respond(exchange, 503, "{\"error\":\"recon disabled; set RECON_BLOTTER_CAPACITY\"}");
                        return null;
                    }
                    final java.util.Optional<finos.traderx.ordermatcher.auth.JwtPrincipal> principal =
                        jwt.validate(exchange.getRequestHeaders().getFirst("Authorization"));
                    if (principal.isEmpty() || !principal.get().admin()) {
                        respond(exchange, 401, "{\"error\":\"admin JWT required\"}");
                        return null;
                    }
                    respond(exchange, 200, json.writeValueAsString(body.call()));
                } catch (final Exception ex) {
                    try {
                        respond(exchange, 500, "{\"error\":"
                            + json.writeValueAsString(String.valueOf(ex.getMessage())) + "}");
                    } catch (final Exception ignore) {
                        // client went away
                    }
                }
                return null;
            };

        server.createContext("/recon/trades/blotter", exchange ->
            guarded.apply(exchange, () -> recon.liveSince(longParam(exchange, "sinceSeq", 0), pageSize)));

        server.createContext("/recon/full-history/reindex", exchange ->
            guarded.apply(exchange, () -> {
                // Bracket the replay with the live trade counter. The replay's own index is a
                // fixed prefix of a log that keeps moving, so "the replay reproduced the live
                // engine" is only assertable as an interval — and stating it as one is what lets a
                // proof fail when the replay is wrong instead of hand-waving a near-miss.
                final long before = service.engine().tradeCounter();
                final ClusterRecon.ReindexResult result = recon.reindexFullHistory();
                final long after = service.engine().tradeCounter();
                return java.util.Map.of(
                    "indexedTrades", result.indexedTrades(),
                    "evictions", result.evictions(),
                    "replayedMessages", result.replayedMessages(),
                    "replayedAppliedSeq", result.replayedAppliedSeq(),
                    "shadowTradeCounter", result.shadowTradeCounter(),
                    "liveTradeCounterBefore", before,
                    "liveTradeCounterAfter", after);
            }));

        server.createContext("/recon/full-history/trades", exchange ->
            guarded.apply(exchange, () -> {
                final java.util.List<?> page =
                    recon.fullHistorySince(longParam(exchange, "sinceSeq", 0), pageSize);
                if (page == null) {
                    throw new IllegalStateException(
                        "no full-history index yet; POST /recon/full-history/reindex first");
                }
                return page;
            }));

        server.createContext("/regulatory/report", exchange ->
            guarded.apply(exchange, () -> recon.regulatoryReport(
                longParam(exchange, "fromSeq", 0), longParam(exchange, "toSeq", 0))));
    }

    private static long longParam(final com.sun.net.httpserver.HttpExchange exchange,
                                  final String name, final long fallback) {
        final String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return fallback;
        }
        for (final String pair : query.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return Long.parseLong(pair.substring(eq + 1));
                } catch (final NumberFormatException ex) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    /** Peer's applied sequence via its /health, or -1 if unreachable/unparsable (ignored). */
    private static long peerApplied(final String hostname, final int port) {
        try {
            final java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                java.net.URI.create("http://" + hostname + ":" + port + "/health").toURL().openConnection();
            conn.setConnectTimeout(300);
            conn.setReadTimeout(300);
            try (java.io.InputStream in = conn.getInputStream()) {
                final String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                final java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("\"applied\":(-?\\d+)").matcher(body);
                return m.find() ? Long.parseLong(m.group(1)) : -1;
            }
        } catch (final Exception e) {
            return -1;
        }
    }

    private static void respond(final com.sun.net.httpserver.HttpExchange exchange, final int code,
                                final String body) throws java.io.IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String env(final String name, final String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
