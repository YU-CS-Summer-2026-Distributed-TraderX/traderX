package finos.traderx.ordermatcher.cluster;

import com.sun.net.httpserver.HttpServer;
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
        final ClusterNodeConfig.Contexts contexts =
            ClusterNodeConfig.contexts(memberId, hostnames, portBase, aeronDir, baseDir, service, false);
        contexts.consensusModule().terminationHook(() -> {
            System.err.println("Consensus module terminated; exiting for pod restart");
            Runtime.getRuntime().halt(70);
        });

        final ClusteredMediaDriver driver = ClusteredMediaDriver.launch(
            contexts.mediaDriver(), contexts.archive(), contexts.consensusModule());
        final ClusteredServiceContainer container = ClusteredServiceContainer.launch(contexts.container());
        final HttpServer health = healthServer(healthPort, memberId, hostnames, service);
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

    private static HttpServer healthServer(final int port, final int memberId,
                                           final List<String> hostnames,
                                           final MatchingEngineClusteredService service) throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", exchange -> {
            final boolean started = service.engine() != null;
            final String body = "{\"memberId\":" + memberId
                + ",\"role\":\"" + service.role() + "\""
                + ",\"started\":" + started
                + ",\"applied\":" + (started ? service.engine().blpSeq() : -1)
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
        server.createContext("/ready", exchange -> {
            final boolean started = service.engine() != null;
            if (!started) {
                respond(exchange, 503, "{\"ready\":false,\"reason\":\"not started\"}");
                return;
            }
            final long mine = service.engine().blpSeq();
            long maxPeer = -1;
            for (int i = 0; i < hostnames.size(); i++) {
                if (i == memberId) {
                    continue;
                }
                maxPeer = Math.max(maxPeer, peerApplied(hostnames.get(i), port));
            }
            final boolean ready = maxPeer < 0 || mine >= maxPeer - maxLag;
            respond(exchange, ready ? 200 : 503, "{\"ready\":" + ready
                + ",\"applied\":" + mine + ",\"maxPeerApplied\":" + maxPeer + "}");
        });
        // Prometheus scrape surface (Grafana YU12 dashboard): each member exports its own signals
        // labelled by memberId, so Prometheus scraping all three renders per-node role/lag/snapshots.
        server.createContext("/metrics", exchange -> {
            final boolean started = service.engine() != null;
            final int role = service.role() == io.aeron.cluster.service.Cluster.Role.LEADER ? 1 : 0;
            final long applied = started ? service.engine().blpSeq() : 0;
            final long trades = started ? service.engine().tradeCounter() : 0;
            final String m = "{member=\"" + memberId + "\"} ";
            final String body =
                  "# TYPE traderx_cluster_role gauge\ntraderx_cluster_role" + m + role + "\n"
                + "# TYPE traderx_cluster_applied counter\ntraderx_cluster_applied" + m + applied + "\n"
                + "# TYPE traderx_cluster_trades counter\ntraderx_cluster_trades" + m + trades + "\n"
                + "# TYPE traderx_cluster_snapshots counter\ntraderx_cluster_snapshots" + m + service.snapshotsTaken() + "\n"
                + "# TYPE traderx_cluster_up gauge\ntraderx_cluster_up" + m + (started ? 1 : 0) + "\n";
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
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
