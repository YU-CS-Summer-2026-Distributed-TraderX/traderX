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
        // A killed member restarts faster than its own Aeron mark files go stale: the heartbeat
        // stops on kill, but until each file's liveness timeout (<=10s) elapses,
        // MarkFile.mapNewOrExistingMarkFile reads it as another live process and launch dies with
        // "active mark file detected" (observed on kind 2026-08-24: every liveness kill cost 2-3
        // restarts instead of 1, on all three members). Same class as the DNS race above: a
        // transient startup condition to wait out, bounded, not crash on. Retrying the launch
        // itself re-evaluates exactly the check that throws, whichever of the four mark files
        // (archive, consensus, service, driver) it is; contexts are rebuilt per attempt because
        // a concluded context cannot be reused. 60s = 6x the mark-file liveness timeout, margin
        // for the CPU starvation that caused the kill; past that, an active mark file means
        // another LIVE process owns the dirs, which waiting cannot fix — terminal.
        ClusteredMediaDriver launchedDriver = null;
        ClusteredServiceContainer launchedContainer = null;
        final long markFileDeadline = System.currentTimeMillis() + 60_000;
        while (true) {
            final ClusterNodeConfig.Contexts contexts =
                ClusterNodeConfig.contexts(memberId, hostnames, portBase, aeronDir, baseDir, service, false);
            contexts.consensusModule().terminationHook(() -> {
                System.err.println("Consensus module terminated; exiting for pod restart");
                Runtime.getRuntime().halt(70);
            });
            try {
                launchedDriver = ClusteredMediaDriver.launch(
                    contexts.mediaDriver(), contexts.archive(), contexts.consensusModule());
                launchedContainer = ClusteredServiceContainer.launch(contexts.container());
                break;
            } catch (final RuntimeException e) {
                if (e.getMessage() == null || !e.getMessage().contains("active mark file")) {
                    throw e;
                }
                CloseHelper.quietCloseAll(launchedContainer, launchedDriver);
                launchedContainer = null;
                launchedDriver = null;
                if (System.currentTimeMillis() > markFileDeadline) {
                    // halt, not throw: a throw out of main leaves surviving non-daemon threads
                    // holding the pod Running-with-no-health-server (the zombie the liveness
                    // probe then takes ~5min to clear); halt guarantees the fast restart path.
                    System.err.println("mark file still active after 60s of waiting -- another"
                        + " live process owns " + baseDir + "; not a restart race");
                    e.printStackTrace();
                    Runtime.getRuntime().halt(1);
                }
                System.out.println("Waiting for mark file release: " + e.getMessage());
                Thread.sleep(2_000);
            }
        }
        final ClusteredMediaDriver driver = launchedDriver;
        final ClusteredServiceContainer container = launchedContainer;
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
                + ",\"snapshots\":" + service.snapshotsTaken()
                // YU17 (ADR-069 §1.7): "was the market open, and is anything queued?" must be
                // answerable in ONE request, from the first commit — the vacuous-pass
                // countermeasure for a halt that would otherwise be invisible until something
                // failed to trade. Both are replicated state read off this member's own engine
                // service, so three members disagreeing here is a real finding.
                + ",\"phase\":\"" + service.phaseName() + "\""
                + ",\"queueDepth\":" + service.queueDepth() + "}";
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
        // Book-derived market data (ADR-067's undisputed slice): the BBO and mark each member can
        // compute from its own applied state, exported BESIDE consensus -- nothing here is
        // sequenced, because every member already holds it and all three answer identically at one
        // applied position (which is why `applied` rides along: two scrapes agree only when it
        // does). A one-sided book reports the side it has; absent sides are omitted, and no
        // midpoint is synthesized -- a midpoint of one side is not a midpoint (ADR-067 q2).
        // Prices are emitted at the full 6dp tick scale, NOT Px.toBigDecimal's 3dp edge rounding:
        // a Treasury's fraction-of-par carries six decimals (ADR-057) and 3dp destroys it.
        // Cold path, same posture as the digest in /metrics: scrape deliberately.
        server.createContext("/bbo", exchange -> {
            if (service.engine() == null) {
                respond(exchange, 503, "{\"error\":\"engine not started\"}");
                return;
            }
            final StringBuilder sb = new StringBuilder(4096);
            sb.append("{\"member\":").append(memberId)
              .append(",\"applied\":").append(service.appliedSeq())
              .append(",\"books\":[");
            boolean first = true;
            for (int id = 0; ; id++) {
                final String ticker = service.tickerFor(id);
                if (ticker == null) {
                    break;
                }
                final long bid = service.engine().bestBidPx(id);
                final long ask = service.engine().bestAskPx(id);
                final long mark = service.engine().markPx(id);
                if (bid == 0L && ask == 0L && mark == 0L) {
                    continue; // never quoted, never traded, nothing resting: no row at all
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"ticker\":\"").append(ticker).append('"');
                if (bid != 0L) {
                    sb.append(",\"bid\":").append(java.math.BigDecimal.valueOf(bid, 6).toPlainString());
                }
                if (ask != 0L) {
                    sb.append(",\"ask\":").append(java.math.BigDecimal.valueOf(ask, 6).toPlainString());
                }
                if (mark != 0L) {
                    sb.append(",\"mark\":").append(java.math.BigDecimal.valueOf(mark, 6).toPlainString());
                }
                // YU17 (format-8 design §2.6): the grid this book is actually on. One request
                // answers "what precision will the engine accept here" — which is what lets the
                // console ticket derive its step from the venue instead of guessing, closing the
                // UI landmine where a form offers precision the engine refuses. 0 = no book has
                // ever been created for this security, so there is no grid to report yet.
                final long tickPx = service.engine().bookTickPxOf(id);
                if (tickPx != 0L) {
                    sb.append(",\"tickPx\":").append(tickPx);
                    // Scale drift: this book is OCCUPIED on a grid its current reference would no
                    // longer derive. It heals on its own the moment the book empties, so this is an
                    // operator signal ("which books are on a stale grid"), never an alarm — and the
                    // remedy is to cancel the residents or wait for turnover, never an epoch.
                    if (service.engine().bookTickDrifted(id)) {
                        sb.append(",\"tickDrift\":true");
                    }
                }
                sb.append('}');
            }
            respond(exchange, 200, sb.append("]}").toString());
        });
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
                // ADR-066: bands re-centred on the market, and resting orders that cancelled for it.
                + "# TYPE traderx_band_reanchors counter\ntraderx_band_reanchors" + m
                + (started ? service.engine().bandReanchors() : 0L) + "\n"
                + "# TYPE traderx_band_stranded_cancels counter\ntraderx_band_stranded_cancels" + m
                + (started ? service.engine().bandStrandedCancels() : 0L) + "\n"
                // YU17 (format-8 design 2.6): empty-book grid re-derivations that CHANGED a tick.
                // PER-PROCESS, exactly like the two ADR-066 counters above and for the same reason:
                // a plain in-process field on MatchingEngine, deliberately NOT snapshotted, because
                // pulling observability into the deterministic state machine would turn a metrics
                // artefact into a digest divergence. So a member's ABSOLUTE value is a function of
                // how much log THAT process has applied since it started -- a restarted member reads
                // lower than its peers on a cluster in perfect agreement. Assert the per-member
                // DELTA against a captured baseline; cross-member equality of absolutes cannot pass.
                + "# TYPE traderx_book_reticks counter\ntraderx_book_reticks" + m
                + (started ? service.engine().bookReticks() : 0L) + "\n"
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
    /**
     * A capability that is present but not yet ready, answered 503 rather than 500. Distinct from a
     * genuine failure so the generic handler cannot swallow it: a caller seeing 500 raises an
     * incident, a caller seeing 503 runs the reindex the message names.
     */
    private static final class ReconNotReadyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ReconNotReadyException(final String message) {
            super(message);
        }
    }

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
                } catch (final ReconNotReadyException ex) {
                    // 503, not 500. "Not reindexed yet" is the same class of condition as "recon
                    // disabled" above -- the capability is present and the caller should retry
                    // after driving the documented next step -- whereas 500 says the server broke
                    // and invites an alert instead of a reindex. ClusterRecon documents this
                    // contract ("the caller answers 503, same as the Spring tier") and the Spring
                    // tier's own paging returns 503 "reindex first"; the generic handler below was
                    // silently answering 500 and breaking parity between the two tiers.
                    try {
                        respond(exchange, 503, "{\"error\":"
                            + json.writeValueAsString(String.valueOf(ex.getMessage())) + "}");
                    } catch (final Exception ignore) {
                        // client went away
                    }
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
                    throw new ReconNotReadyException(
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
