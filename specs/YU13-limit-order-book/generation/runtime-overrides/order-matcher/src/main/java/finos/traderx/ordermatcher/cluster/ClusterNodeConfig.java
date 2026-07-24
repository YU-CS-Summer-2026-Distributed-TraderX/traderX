package finos.traderx.ordermatcher.cluster;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.NoOpLock;
import org.agrona.concurrent.YieldingIdleStrategy;

import java.io.File;
import java.util.List;
import java.util.function.Supplier;

/**
 * Canonical per-member Aeron Cluster wiring (mirrors the Aeron 1.51.0 sample
 * {@code io.aeron.samples.cluster.ClusterConfig} port/endpoint scheme): each member owns a
 * 100-port block at {@code portBase + memberId * 100} with fixed offsets, the member string
 * carries ingress/consensus/log/transfer/archive endpoints, the consensus module and service
 * container reach their co-located archive over local IPC, and ingress/log channels stay at
 * cluster defaults so every endpoint derives from the member string. Used by the in-process
 * three-member proof and the containerized cluster node entrypoint.
 */
public final class ClusterNodeConfig {
    public static final int PORTS_PER_NODE = 100;
    public static final int ARCHIVE_CONTROL_PORT_OFFSET = 1;
    public static final int CLIENT_FACING_PORT_OFFSET = 2;
    public static final int MEMBER_FACING_PORT_OFFSET = 3;
    public static final int LOG_PORT_OFFSET = 4;
    public static final int TRANSFER_PORT_OFFSET = 5;
    // Fixed ports for the channels Aeron's sample config leaves ephemeral (`:0`). An ephemeral
    // bind escapes any port-scoped NetworkPolicy: empty-disk join replicates the log/recordings
    // onto these channels, and a policy that only admits the 21800+ block silently drops them —
    // the joiner then loops INIT->CANVASS->FOLLOWER_LOG_REPLICATION at applied=-1 forever (the
    // wedge previously misattributed to an Aeron 1.51 defect, T-LOB16). PVC-preserving restarts
    // hid it because FOLLOWER_CATCHUP uses the in-block transfer port.
    public static final int ARCHIVE_RESPONSE_PORT_OFFSET = 6;
    public static final int ARCHIVE_REPLICATION_PORT_OFFSET = 7;
    public static final int CM_REPLICATION_PORT_OFFSET = 8;

    /** Everything needed to launch one member: pass the first three to
     *  {@code ClusteredMediaDriver.launch} and the last to {@code ClusteredServiceContainer.launch}. */
    public record Contexts(MediaDriver.Context mediaDriver, Archive.Context archive,
                           ConsensusModule.Context consensusModule,
                           ClusteredServiceContainer.Context container) {
    }

    private ClusterNodeConfig() {
    }

    public static int port(final int memberId, final int portBase, final int offset) {
        return portBase + memberId * PORTS_PER_NODE + offset;
    }

    /** `id,ingress,consensus,log,transfer,archive-control|...` for every member. */
    public static String clusterMembers(final List<String> hostnames, final int portBase) {
        final StringBuilder sb = new StringBuilder();
        for (int id = 0; id < hostnames.size(); id++) {
            final String host = hostnames.get(id);
            sb.append(id)
                .append(',').append(host).append(':').append(port(id, portBase, CLIENT_FACING_PORT_OFFSET))
                .append(',').append(host).append(':').append(port(id, portBase, MEMBER_FACING_PORT_OFFSET))
                .append(',').append(host).append(':').append(port(id, portBase, LOG_PORT_OFFSET))
                .append(',').append(host).append(':').append(port(id, portBase, TRANSFER_PORT_OFFSET))
                .append(',').append(host).append(':').append(port(id, portBase, ARCHIVE_CONTROL_PORT_OFFSET))
                .append('|');
        }
        return sb.toString();
    }

    /** `0=host:port,1=host:port,...` for {@code AeronCluster.Context.ingressEndpoints}. */
    public static String ingressEndpoints(final List<String> hostnames, final int portBase) {
        final StringBuilder sb = new StringBuilder();
        for (int id = 0; id < hostnames.size(); id++) {
            sb.append(id).append('=')
                .append(hostnames.get(id)).append(':').append(port(id, portBase, CLIENT_FACING_PORT_OFFSET))
                .append(',');
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /**
     * Opt-in non-spinning idle for LOCAL correctness work (kind), off by default.
     *
     * <p>A member runs four Aeron agent threads — media driver (SHARED), archive (SHARED),
     * consensus module, clustered service — and Aeron's default backoff idle parks for at most 1ms,
     * so an IDLE member still burns most of a core. Measured on a kind cluster: four nodes at
     * 145–205% CPU each on an 11-CPU Docker VM doing nothing at all. That is what produced a 2.3x
     * run-to-run throughput spread on identical bytes, the SnapshotBarrier 50ms timing flake, the
     * ThreeMemberCluster election timeout, and eventually a kube-apiserver that fell over
     * mid-proof.
     *
     * <p>{@code CLUSTER_IDLE_SLEEP_MS=1} trades that away for a millisecond of latency per idle
     * poll. It is the right trade for the thing kind is actually for — correctness and HA proofs,
     * where the assertions are on state, not on time — and it DISQUALIFIES the deployment for any
     * latency or throughput number. Default 0 = Aeron's own strategies, so every measurement taken
     * before this existed remains reproducible.
     */
    static long sleepingIdleMs() {
        final String v = System.getenv("CLUSTER_IDLE_SLEEP_MS");
        return v == null || v.isEmpty() ? 0L : Long.parseLong(v);
    }

    static IdleStrategy sleepingIdle() {
        return new SleepingMillisIdleStrategy(sleepingIdleMs());
    }

    /**
     * LATENCY-01 lever: override the idle strategy on ALL member Aeron agents (media driver, archive,
     * consensus module, service container) so the ~4ms per-order latency — measured to be dominated by
     * Aeron's DEFAULT BackoffIdleStrategy parking up to 1ms per idle poll — can be A/B'd against a
     * non-parking strategy. Precedence: CLUSTER_IDLE_STRATEGY > CLUSTER_IDLE_SLEEP_MS > Aeron default.
     * <ul>
     *   <li>{@code yielding} — spin then {@code Thread.yield()}, no park; shares the member's 3-core
     *       pin across its 4 agent threads (the safe pick on this hardware — pure spin would
     *       oversubscribe 4 spinners onto 3 exclusive cores).</li>
     *   <li>{@code busyspin} — NoOp pure spin: lowest latency, but wants one core per agent.</li>
     *   <li>{@code lowpark} — Backoff with a 1µs max park (vs the 1ms default): a middle ground.</li>
     *   <li>{@code backoff} — the Aeron default, stated explicitly.</li>
     * </ul>
     * Returns null when unset, so the existing sleeping/default logic is untouched and every prior
     * measurement stays reproducible.
     */
    static Supplier<IdleStrategy> idleOverrideSupplier() {
        final String s = System.getenv("CLUSTER_IDLE_STRATEGY");
        if (s == null || s.isEmpty()) {
            return null;
        }
        switch (s.trim().toLowerCase()) {
            case "yielding": case "yield": return YieldingIdleStrategy::new;
            case "busyspin": case "noop":  return NoOpIdleStrategy::new;
            case "lowpark":  return () -> new BackoffIdleStrategy(10, 5, 1000, 1_000L);
            case "backoff":  return () -> new BackoffIdleStrategy(10, 5, 1000, 1_000_000L);
            default: throw new IllegalArgumentException("unknown CLUSTER_IDLE_STRATEGY: " + s);
        }
    }

    /**
     * LATENCY-02 lever: run the cluster clock in NANOSECONDS instead of Aeron's default milliseconds,
     * so the sequencing timestamp handed to {@code onSessionMessage} can resolve the consensus commit
     * round-trip. On the default ms clock the commit is a 1ms quantum per sample — which is why the
     * post-lowpark commit read as "a flat 1000µs to p99.9": at that point the true value had fallen
     * under the measurement's own resolution.
     *
     * <p>Safe here because (a) the service converts the timestamp back to millis before it touches
     * replicated state, a deterministic conversion identical on every member and on replay, and (b)
     * this service schedules no cluster timers, so nothing else reads cluster time. Off by default,
     * so every prior measurement stays reproducible.
     *
     * <p>Must be set identically on all members — it changes the unit of the timestamp written into
     * the log, so a mixed-clock cluster is a mixed-version cluster.
     */
    static boolean nanosClusterClock() {
        final String v = System.getenv("CLUSTER_CLOCK");
        return v != null && v.trim().equalsIgnoreCase("nanos");
    }

    public static Contexts contexts(final int memberId, final List<String> hostnames, final int portBase,
                                    final String aeronDir, final File baseDir,
                                    final ClusteredService service, final boolean cleanStart) {
        final String host = hostnames.get(memberId);
        final File archiveDir = new File(baseDir, "archive");
        final File clusterDir = new File(baseDir, "cluster");
        // LATENCY-01 lever (precedence: explicit strategy > sleeping-ms > Aeron default).
        final Supplier<IdleStrategy> idleOverride = idleOverrideSupplier();

        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true);
        if (idleOverride != null) {
            mediaDriverContext.sharedIdleStrategy(idleOverride.get());
        } else if (sleepingIdleMs() > 0) {
            mediaDriverContext.sharedIdleStrategy(sleepingIdle());
        }

        final Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(aeronDir)
            .archiveDir(archiveDir)
            .controlChannel("aeron:udp?endpoint=" + host + ":" + port(memberId, portBase, ARCHIVE_CONTROL_PORT_OFFSET))
            .archiveClientContext(new AeronArchive.Context()
                .controlResponseChannel("aeron:udp?endpoint=" + host + ":" + port(memberId, portBase, ARCHIVE_RESPONSE_PORT_OFFSET)))
            .localControlChannel("aeron:ipc?term-length=64k")
            .replicationChannel("aeron:udp?endpoint=" + host + ":" + port(memberId, portBase, ARCHIVE_REPLICATION_PORT_OFFSET))
            .recordingEventsEnabled(false)
            .threadingMode(ArchiveThreadingMode.SHARED)
            .deleteArchiveOnStart(cleanStart);
        if (idleOverride != null) {
            archiveContext.idleStrategySupplier(idleOverride);
        } else if (sleepingIdleMs() > 0) {
            archiveContext.idleStrategySupplier(ClusterNodeConfig::sleepingIdle);
        }

        final AeronArchive.Context localArchiveClient = new AeronArchive.Context()
            .lock(NoOpLock.INSTANCE)
            .controlRequestChannel(archiveContext.localControlChannel())
            .controlRequestStreamId(archiveContext.localControlStreamId())
            .controlResponseChannel(archiveContext.localControlChannel())
            .aeronDirectoryName(aeronDir);

        final ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
            .clusterMemberId(memberId)
            .clusterMembers(clusterMembers(hostnames, portBase))
            .ingressChannel("aeron:udp?term-length=64k")
            .clusterDir(clusterDir)
            .archiveContext(localArchiveClient.clone())
            .serviceCount(1)
            .replicationChannel("aeron:udp?endpoint=" + host + ":" + port(memberId, portBase, CM_REPLICATION_PORT_OFFSET))
            .deleteDirOnStart(cleanStart);
        // Failover speed lever (NFR-AC03): the Aeron defaults (leaderHeartbeatTimeout 10s) put
        // failover detection at ~10-12s on ANY hardware — confirmed on GKE. These make detection
        // and election aggressive so client-observed failover drops to the low seconds / sub-second.
        // Env-tunable (ns = ms * 1e6) so the sweet spot can be found without a rebuild; 0 = keep
        // the Aeron default. Constraint: heartbeatTimeout > heartbeatInterval, election >= interval.
        applyTimeoutMs(consensusModuleContext);
        if (nanosClusterClock()) {
            consensusModuleContext.clusterClock(new NanosClusterClock());
        }
        if (idleOverride != null) {
            consensusModuleContext.idleStrategySupplier(idleOverride);
        } else if (sleepingIdleMs() > 0) {
            consensusModuleContext.idleStrategySupplier(ClusterNodeConfig::sleepingIdle);
        }

        final ClusteredServiceContainer.Context containerContext = new ClusteredServiceContainer.Context()
            .aeronDirectoryName(aeronDir)
            .archiveContext(localArchiveClient.clone())
            .clusterDir(clusterDir)
            .clusteredService(service)
            .serviceId(0);
        if (idleOverride != null) {
            containerContext.idleStrategySupplier(idleOverride);
        } else if (sleepingIdleMs() > 0) {
            containerContext.idleStrategySupplier(ClusterNodeConfig::sleepingIdle);
        }

        return new Contexts(mediaDriverContext, archiveContext, consensusModuleContext, containerContext);
    }

    /** Apply env-provided consensus timeouts (milliseconds; 0/unset = Aeron default). */
    private static void applyTimeoutMs(final ConsensusModule.Context ctx) {
        final long intervalMs = envLong("CLUSTER_HEARTBEAT_INTERVAL_MS", 0);
        final long timeoutMs = envLong("CLUSTER_HEARTBEAT_TIMEOUT_MS", 0);
        final long electionMs = envLong("CLUSTER_ELECTION_TIMEOUT_MS", 0);
        final long canvassMs = envLong("CLUSTER_STARTUP_CANVASS_TIMEOUT_MS", 0);
        if (intervalMs > 0) {
            ctx.leaderHeartbeatIntervalNs(intervalMs * 1_000_000L);
        }
        if (timeoutMs > 0) {
            ctx.leaderHeartbeatTimeoutNs(timeoutMs * 1_000_000L);
        }
        if (electionMs > 0) {
            ctx.electionTimeoutNs(electionMs * 1_000_000L);
        }
        if (canvassMs > 0) {
            ctx.startupCanvassTimeoutNs(canvassMs * 1_000_000L);
        }
        // Phase 3 (joint plan): the canvass-position publication quantum — Aeron default 100 ms —
        // bounds how fast an election can advance past CANVASS; it must shrink with the rest of
        // the ladder or it dominates the budget below ~200 ms profiles.
        final long statusMs = envLong("CLUSTER_ELECTION_STATUS_INTERVAL_MS", 0);
        if (statusMs > 0) {
            ctx.electionStatusIntervalNs(statusMs * 1_000_000L);
        }
    }

    private static long envLong(final String name, final long fallback) {
        final String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : Long.parseLong(v);
    }
}
