package finos.traderx.ordermatcher.cluster;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.concurrent.NoOpLock;

import java.io.File;
import java.util.List;

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

    public static Contexts contexts(final int memberId, final List<String> hostnames, final int portBase,
                                    final String aeronDir, final File baseDir,
                                    final ClusteredService service, final boolean cleanStart) {
        final String host = hostnames.get(memberId);
        final File archiveDir = new File(baseDir, "archive");
        final File clusterDir = new File(baseDir, "cluster");

        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .termBufferSparseFile(true)
            .dirDeleteOnStart(true);

        final Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(aeronDir)
            .archiveDir(archiveDir)
            .controlChannel("aeron:udp?endpoint=" + host + ":" + port(memberId, portBase, ARCHIVE_CONTROL_PORT_OFFSET))
            .archiveClientContext(new AeronArchive.Context()
                .controlResponseChannel("aeron:udp?endpoint=" + host + ":0"))
            .localControlChannel("aeron:ipc?term-length=64k")
            .replicationChannel("aeron:udp?endpoint=" + host + ":0")
            .recordingEventsEnabled(false)
            .threadingMode(ArchiveThreadingMode.SHARED)
            .deleteArchiveOnStart(cleanStart);

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
            .replicationChannel("aeron:udp?endpoint=" + host + ":0")
            .deleteDirOnStart(cleanStart);
        // Failover speed lever (NFR-AC03): the Aeron defaults (leaderHeartbeatTimeout 10s) put
        // failover detection at ~10-12s on ANY hardware — confirmed on GKE. These make detection
        // and election aggressive so client-observed failover drops to the low seconds / sub-second.
        // Env-tunable (ns = ms * 1e6) so the sweet spot can be found without a rebuild; 0 = keep
        // the Aeron default. Constraint: heartbeatTimeout > heartbeatInterval, election >= interval.
        applyTimeoutMs(consensusModuleContext);

        final ClusteredServiceContainer.Context containerContext = new ClusteredServiceContainer.Context()
            .aeronDirectoryName(aeronDir)
            .archiveContext(localArchiveClient.clone())
            .clusterDir(clusterDir)
            .clusteredService(service)
            .serviceId(0);

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
    }

    private static long envLong(final String name, final long fallback) {
        final String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : Long.parseLong(v);
    }
}
