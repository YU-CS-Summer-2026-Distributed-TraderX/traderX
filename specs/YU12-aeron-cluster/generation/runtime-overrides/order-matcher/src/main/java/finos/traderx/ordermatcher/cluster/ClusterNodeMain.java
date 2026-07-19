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
 * role, applied sequence, and snapshot count; {@code /ready} is 200 once the service has
 * started (leadership and catch-up are the cluster's own concern, not a pod-readiness gate —
 * admission readiness lives at the gateway tier per ADR-045).
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
        final HttpServer health = healthServer(healthPort, memberId, service);
        startSnapshotTrigger(aeronDir, service);

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
        server.createContext("/ready", exchange ->
            respond(exchange, service.engine() != null ? 200 : 503, service.role().toString()));
        server.start();
        return server;
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
