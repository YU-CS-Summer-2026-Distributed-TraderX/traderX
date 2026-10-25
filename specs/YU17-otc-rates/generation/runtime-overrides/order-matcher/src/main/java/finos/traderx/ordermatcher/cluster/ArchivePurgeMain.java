package finos.traderx.ordermatcher.cluster;

import io.aeron.Aeron;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.RecordingLog;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Archive truncation behind snapshots: bounds a cluster member's Aeron archive by purging log segments behind the
 * SECOND-latest complete snapshot, and snapshot recordings older than it. Without it the archive grows ~125 B per
 * order (~108 GB/h at 250K/s) and nothing ever shrinks it. Proven on the hxb harness at 250K/s for 45 min (/data
 * plateaued at 3.8 GB under an 8 GiB cap; strict snapshot+tail replay passed; snapshots byte-identical on 3 members).
 *
 * <p>Runs as its own small JVM next to the member (same container, same /dev/shm aeron dir, same /data), so the
 * member image's classes are untouched. Reads the cluster's RecordingLog read-only (as ClusterRecon does) and talks to
 * the co-located archive over its local IPC control channel. Each member purges only its own archive.
 *
 * <p>Safety: the purge bound is S0 (second-latest snapshot), never S1, so recovery keeps a fallback snapshot and every
 * follower keeps one whole snapshot interval of catch-up log. Restart from the member's own disk is unaffected (snapshot + tail); a member
 * joining with an EMPTY disk needs the log from 0 and cannot catch up once it is purged, so recover such a member from
 * a peer's disk copy, or leave this off. It purges only when every peer's /health answers and its
 * applied sequence is within CLUSTER_READY_MAX_LAG of the furthest member; otherwise it HOLDS, unless the archive
 * exceeds ARCHIVE_PURGE_FORCE_PCT of ARCHIVE_PURGE_DATA_LIMIT_BYTES, then it purges anyway and says PURGE_FORCED.
 *
 * <p>Args: clusterDir aeronDir. Env (shared with ClusterNodeMain): CLUSTER_MEMBER_ID, CLUSTER_HOSTNAMES, HEALTH_PORT,
 * CLUSTER_READY_MAX_LAG; own: ARCHIVE_PURGE_POLL_MS (2000), ARCHIVE_PURGE_DATA_LIMIT_BYTES (0 = no force),
 * ARCHIVE_PURGE_FORCE_PCT (70), ARCHIVE_PURGE_HOLD_FILE (while that file exists, no pass runs: hold it while a checker
 * reads the archive), ARCHIVE_PURGE_DELETE_PER_POLL (1).
 *
 * <p>Detach, then delete off the archive thread: purgeSegments made the archive unlink ~14 x 128 MiB segment files per
 * pass on its conductor, which in the member's SHARED archive mode is the recorder's thread, and each pass stalled commit
 * 80-100 ms at 250K/s. A pass now only detachSegments (a catalog change: the start position moves, the files stay), and
 * this process unlinks the detached files itself, ARCHIVE_PURGE_DELETE_PER_POLL per poll; at 250K/s the log writes one
 * segment per ~4.3 s and a poll is 2 s, so one per poll keeps up. Above the force threshold the whole backlog goes at
 * once. Old snapshot recordings (a few MB each) are still purgeRecording'd on the archive. Proven at 250K/s for 45 min:
 * passes 9-84 ms, /data flat at 3.7-3.8 GB, snapshot+tail replay valid. Start: {@code java -cp /opt/app/classes:/opt/app/lib/* finos.traderx.ordermatcher.cluster.ArchivePurgeMain
 * /data/cluster /dev/shm/aeron-...} next to the member, with the member's env; it needs
 * {@code --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED} like the member JVM.
 */
public final class ArchivePurgeMain implements AutoCloseable {
    static final String ARCHIVE_LOCAL_CONTROL = "aeron:ipc?term-length=64k";   // ClusterNodeConfig's localControlChannel
    static final int CONTROL_RESPONSE_STREAM_ID = 20_779;   // ClusterRecon 20_777 + replay 20_778
    private static final Pattern APPLIED = Pattern.compile("\"applied\":(-?\\d+)");

    private final File clusterDir;
    private final File dataDir;
    private final File archiveDir;
    private final int deletePerPoll;
    private final List<String> peerHealthUrls;      // every member's /health, including this one's
    private final long maxLag;
    private final long dataLimitBytes;
    private final int forcePct;
    private final Aeron aeron;
    private final AeronArchive archive;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
    private long lastS1 = Long.MIN_VALUE;
    private boolean lastHeld;

    public ArchivePurgeMain(final File clusterDir, final String aeronDir, final List<String> peerHealthUrls,
                          final long maxLag, final long dataLimitBytes, final int forcePct) {
        this.clusterDir = clusterDir;
        this.dataDir = clusterDir.getAbsoluteFile().getParentFile();
        this.archiveDir = new File(dataDir, "archive");                     // ClusterNodeConfig: baseDir/archive
        this.deletePerPoll = Integer.parseInt(env("ARCHIVE_PURGE_DELETE_PER_POLL", "1"));
        this.peerHealthUrls = peerHealthUrls;
        this.maxLag = maxLag;
        this.dataLimitBytes = dataLimitBytes;
        this.forcePct = forcePct;
        this.aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
        this.archive = AeronArchive.connect(new AeronArchive.Context()
            .aeron(aeron).ownsAeronClient(false)
            .controlRequestChannel(ARCHIVE_LOCAL_CONTROL)
            .controlRequestStreamId(AeronArchive.Configuration.localControlStreamId())
            .controlResponseChannel("aeron:ipc")
            .controlResponseStreamId(CONTROL_RESPONSE_STREAM_ID));
    }

    public static void main(final String[] args) throws Exception {
        final String me = env("CLUSTER_MEMBER_ID", env("HOSTNAME", "?"));
        final String port = env("HEALTH_PORT", "8080");
        final List<String> urls = new ArrayList<>();
        for (final String h : env("CLUSTER_HOSTNAMES", "localhost").split(",")) {
            urls.add("http://" + h.trim() + ":" + port + "/health");
        }
        System.out.println("PURGE_START member=" + me + " peers=" + urls);
        final long pollMs = Long.parseLong(env("ARCHIVE_PURGE_POLL_MS", "2000"));
        final String hold = System.getenv("ARCHIVE_PURGE_HOLD_FILE");
        while (true) {   // the member may not be up yet, or restarts under us: reconnect forever
            try (ArchivePurgeMain p = new ArchivePurgeMain(new File(args[0]), args[1], urls,
                    Long.parseLong(env("CLUSTER_READY_MAX_LAG", "5000")),
                    Long.parseLong(env("ARCHIVE_PURGE_DATA_LIMIT_BYTES", "0")),
                    Integer.parseInt(env("ARCHIVE_PURGE_FORCE_PCT", "70")))) {
                while (true) {
                    if (hold == null || !new File(hold).exists()) p.poll();
                    Thread.sleep(pollMs);
                }
            } catch (final InterruptedException e) {
                return;
            } catch (final Exception e) {
                System.out.println("PURGE_ERROR " + e);
                Thread.sleep(pollMs);
            }
        }
    }

    /** One poll: a pass when a new snapshot set has completed (or the previous pass held), then up to deletePerPoll
     *  detached segment files deleted. Returns the pass line, or null when no pass ran. */
    public String poll() {
        final Snapshots s = snapshots();
        if (s == null) {
            return null;
        }
        String line = null;
        if (s.s1 != lastS1 || lastHeld) {
            lastS1 = s.s1;
            line = pass(s);
        }
        final boolean over = dataLimitBytes > 0 && archiveBytes() * 100 >= dataLimitBytes * forcePct;
        deleteDetached(s.logRecordingIds, over ? Integer.MAX_VALUE : deletePerPoll);
        return line;
    }

    /** Unlink up to `limit` segment files the archive has already detached (base position below the recording's start),
     *  oldest first, in THIS process: the archive's recorder thread never pays for the unlink. Returns files deleted. */
    int deleteDetached(final Set<Long> logRecordingIds, final int limit) {
        final List<long[]> detached = new ArrayList<>();                    // {recordingId, basePosition}
        for (final long id : logRecordingIds) {
            final long start = archive.getStartPosition(id);
            final String[] names = archiveDir.list((d, n) -> n.startsWith(id + "-") && n.endsWith(".rec"));
            if (names == null) continue;
            for (final String n : names) {
                final long base = Long.parseLong(n.substring(n.indexOf('-') + 1, n.length() - 4));
                if (base < start) detached.add(new long[] {id, base});      // floor is segment-aligned: wholly below start
            }
        }
        detached.sort((a, b) -> Long.compare(a[1], b[1]));
        int n = 0;
        for (final long[] f : detached) {
            if (n >= limit) break;
            final File file = new File(archiveDir, f[0] + "-" + f[1] + ".rec");
            final long bytes = file.length(), t0 = System.nanoTime();
            if (file.delete()) {
                n++;
                log("PURGE_DELETE file=" + file.getName() + " bytes=" + bytes + " us=" + (System.nanoTime() - t0) / 1000
                    + " backlog=" + (detached.size() - n));
            }
        }
        return n;
    }

    record Snapshots(long s0, long s1, List<Long> olderRecordingIds, Set<Long> logRecordingIds) { }

    /** Latest two COMPLETE snapshot sets (every service id present at one log position) from the RecordingLog. */
    Snapshots snapshots() {
        final Map<Long, Set<Integer>> services = new HashMap<>();
        final Map<Long, List<Long>> recordings = new HashMap<>();
        final Set<Long> logRecordings = new LinkedHashSet<>();
        final Set<Integer> serviceIds = new LinkedHashSet<>();
        try (RecordingLog log = new RecordingLog(clusterDir, false)) {
            for (final RecordingLog.Entry e : log.entries()) {
                if (!e.isValid) continue;
                if (e.type == RecordingLog.ENTRY_TYPE_TERM) {
                    logRecordings.add(e.recordingId);
                } else if (e.type == RecordingLog.ENTRY_TYPE_SNAPSHOT) {
                    serviceIds.add(e.serviceId);
                    services.computeIfAbsent(e.logPosition, k -> new LinkedHashSet<>()).add(e.serviceId);
                    recordings.computeIfAbsent(e.logPosition, k -> new ArrayList<>()).add(e.recordingId);
                }
            }
        }
        final List<Long> complete = new ArrayList<>();
        services.forEach((pos, ids) -> { if (ids.containsAll(serviceIds)) complete.add(pos); });
        complete.sort(null);
        if (complete.size() < 2) {
            return null;
        }
        final long s1 = complete.get(complete.size() - 1), s0 = complete.get(complete.size() - 2);
        final List<Long> older = new ArrayList<>();
        recordings.forEach((pos, ids) -> { if (pos < s0) older.addAll(ids); });
        return new Snapshots(s0, s1, older, logRecordings);
    }

    String pass(final Snapshots s) {
        final long t0 = System.nanoTime();
        final long before = archiveBytes();
        final String peers = peersBehind(s);
        final boolean force = peers != null && dataLimitBytes > 0 && before * 100 >= dataLimitBytes * forcePct;
        if (peers != null && !force) {
            lastHeld = true;
            return log("PURGE_HELD s0=" + s.s0 + " s1=" + s.s1 + " reason=" + peers + " archiveBytes=" + before);
        }
        lastHeld = false;
        final StringBuilder done = new StringBuilder();
        for (final long id : s.logRecordingIds) {
            final long[] d = descriptor(id);   // start, stop, termLen, segLen
            if (d == null) continue;
            final long bound = d[1] == AeronArchive.NULL_POSITION ? s.s0 : Math.min(s.s0, d[1]);
            final long floor = AeronArchive.segmentFileBasePosition(d[0], bound, (int) d[2], (int) d[3]);
            if (floor > d[0]) {
                archive.detachSegments(id, floor);                          // catalog only; files go in deleteDetached
                done.append(" log").append(id).append('=').append(d[0]).append("->").append(floor);
            }
            // ponytail: a stopped earlier-term recording keeps its last segment (≤ segLen per election); purgeRecording
            // of a term recording RecordingLog still names is not attempted until verified against createRecoveryPlan.
        }
        int snaps = 0;
        for (final long id : s.olderRecordingIds) {
            if (descriptor(id) != null) {
                archive.purgeRecording(id);
                snaps++;
            }
        }
        return log((force ? "PURGE_FORCED reason=" + peers + " " : "PURGE ") + "s0=" + s.s0 + " s1=" + s.s1
            + done + " oldSnapshotRecordings=" + snaps + " archiveBytes=" + before + "->" + archiveBytes()
            + " ms=" + (System.nanoTime() - t0) / 1_000_000);
    }

    /** Null when every member answered and is within maxLag of the furthest; otherwise why not. */
    String peersBehind(final Snapshots s) {
        long max = Long.MIN_VALUE, min = Long.MAX_VALUE;
        for (final String url : peerHealthUrls) {
            final long a = applied(url);
            if (a < 0) return "unreachable-or-unstarted:" + url;
            max = Math.max(max, a);
            min = Math.min(min, a);
        }
        return max - min > maxLag ? "lag=" + (max - min) + ">" + maxLag : null;
    }

    private long applied(final String url) {
        try {
            final HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.ofString());
            final Matcher m = APPLIED.matcher(r.body());
            return r.statusCode() == 200 && m.find() ? Long.parseLong(m.group(1)) : -1;
        } catch (final Exception e) {
            return -1;
        }
    }

    /** {start, stop, termBufferLength, segmentFileLength}, or null when the archive has no such recording. */
    long[] descriptor(final long recordingId) {
        final long[] d = new long[4];
        final int n = archive.listRecording(recordingId, (c, corr, id, startTs, stopTs, start, stop, initialTermId,
            segLen, termLen, mtu, sessionId, streamId, channel, srcChannel, src) -> {
            d[0] = start; d[1] = stop; d[2] = termLen; d[3] = segLen;
        });
        return n == 0 ? null : d;
    }

    /** Bytes under /data (archive + cluster dir). Not Files.walk: the archive deletes purged segments
     *  asynchronously, and walk throws on an entry that vanishes mid-walk (found by PurgeLocalTest). */
    long archiveBytes() {
        return bytes(dataDir);
    }

    private static long bytes(final File f) {
        final File[] children = f.listFiles();
        if (children == null) return f.length();   // a file, or a directory that just vanished (0)
        long n = 0;
        for (final File c : children) n += bytes(c);
        return n;
    }

    /** Every line ends in atMs (wall clock), so a pass can be lined up with client latency. */
    private static String log(final String line) {
        final String stamped = line + " atMs=" + System.currentTimeMillis();
        System.out.println(stamped);
        return stamped;
    }

    private static String env(final String name, final String fallback) {
        final String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    AeronArchive archive() {   // test seam
        return archive;
    }

    @Override
    public void close() {
        archive.close();
        aeron.close();
    }
}
