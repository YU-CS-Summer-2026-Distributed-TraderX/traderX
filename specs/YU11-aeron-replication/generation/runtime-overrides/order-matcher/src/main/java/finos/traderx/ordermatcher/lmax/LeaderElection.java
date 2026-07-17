package finos.traderx.ordermatcher.lmax;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Kubernetes Lease-based leader election for the BLP StatefulSet, augmented with a NATS
 * heartbeat for fast failure detection.
 *
 * <h3>Renewal contract (client-go LeaderElector semantics)</h3>
 * The primary renews on a dedicated <em>lease</em> thread every {@code RENEW_INTERVAL_SECONDS}.
 * A renewal is a single optimistic PUT against a cached {@code resourceVersion} (no per-renewal
 * GET). The primary <b>demotes only on proof</b>: either the Lease shows a foreign
 * {@code holderIdentity}, or it has failed to confirm holdership for {@code RENEW_DEADLINE_SECONDS}
 * (at which point a follower may legitimately steal, so it must stop serving). A single timeout,
 * 5xx, or ambiguous 409 is retried, never treated as loss — this removes the false-demote where an
 * ambiguous PUT (that may have landed server-side) demoted a healthy primary.
 *
 * <p>The invariant {@code RENEW_DEADLINE < LEASE_DURATION} guarantees the old primary self-fences
 * before any follower is entitled to acquire, so there is no two-writer overlap.
 *
 * <h3>Detection (two layers)</h3>
 * <ol>
 *   <li><b>NATS heartbeat</b> (fast path): PRIMARY publishes to {@code traderx.blp.heartbeat} every
 *       100 ms on a <em>separate</em> thread from lease renewal, so a slow renewal never silences
 *       the heartbeat. FOLLOWER treats silence beyond {@code HEARTBEAT_TIMEOUT_MS} as a signal to
 *       check for takeover.
 *   <li><b>Kubernetes Lease</b> (safe path): the follower may steal only after observing the Lease
 *       expired ({@code renewTime} older than {@code LEASE_DURATION}). The Lease PUT is the atomic
 *       optimistic-concurrency gate, so split-brain is impossible regardless of heartbeat behaviour.
 * </ol>
 *
 * <p><b>Pod-GET fast path:</b> on heartbeat silence the follower GETs the holder's Pod object; if the
 * pod is gone (404) the process provably isn't running and the follower steals immediately, so a
 * long Lease does not slow real-death failover (~2-3 s) — only a wedged-but-alive primary pays the
 * full {@code LEASE_DURATION}.
 */
public final class LeaderElection {
    private static final Logger log = LoggerFactory.getLogger(LeaderElection.class);

    private static final String LEASE_NAME        = "order-matcher-leader";
    private static final String HEARTBEAT_SUBJECT = "traderx.blp.heartbeat";

    // Heartbeat cadence; staleness threshold is configurable (default 500 ms).
    private static final long TICK_MS = 100;
    // Guard before stealing from a holder whose Pod is merely Terminating (deletionTimestamp set)
    // rather than gone — covers the kubelet-sets-deletionTimestamp-before-SIGTERM race.
    private static final long TERMINATING_GUARD_NS = 1_000_000_000L; // 1 s

    // Tunable timing contract (env-overridable via the StatefulSet). Defaults per the converged spec.
    private final int  leaseDurationSeconds;   // follower may steal only after this staleness
    private final long renewDeadlineNs;        // primary self-fences if unconfirmed this long
    private final long renewIntervalNs;        // primary renews this often
    private final long heartbeatTimeoutNs;     // follower detection threshold

    private final String podName;
    private final byte[] podNameBytes;      // cached to avoid getBytes() allocation on every heartbeat
    private final String namespace;
    private final String apiBase;
    private final HttpClient http;
    private final String token;
    private final ReplicationRole role;
    private final Consumer<ReplicationRole.Role> onRoleChange;
    private final Connection natsConn;

    private ScheduledExecutorService heartbeatScheduler;   // 100 ms cadence, never blocks on lease HTTP
    private ScheduledExecutorService leaseScheduler;       // renew/poll cadence, may block on k8s API

    /** Nanotime of last heartbeat received by follower; 0 = none yet. */
    private volatile long lastHeartbeatNs = 0;

    /** Nanotime of last CONFIRMED successful renewal/acquire; the admission gate reads this. */
    private volatile long lastSuccessfulRenewNs = 0;

    /** Cached Lease resourceVersion for optimistic single-PUT renewal; null forces a GET-resync. */
    private volatile String cachedResourceVersion;

    /** Last observed renewal round-trip latency (ns), for telemetry. */
    private volatile long lastRenewLatencyNanos = 0;

    private final LongAdder demoteForeignHolder = new LongAdder();
    private final LongAdder demoteDeadline = new LongAdder();

    // Terminating-guard bookkeeping (pod-GET fast path).
    private volatile String terminatingHolder;
    private volatile long   terminatingSinceNs;

    /** Active NATS Dispatcher for heartbeat subscription (follower only). */
    private volatile Dispatcher heartbeatDispatcher;
    /** True only while an already-fenced fast-witness primary reconciles the advisory Lease. */
    private volatile boolean witnessReconciliationPending;

    private enum RenewResult { SUCCESS, AMBIGUOUS, FOREIGN_HOLDER }
    private enum PodStatus   { GONE, TERMINATING, ALIVE, UNKNOWN }

    public LeaderElection(String podName, String namespace, ReplicationRole role,
                          Consumer<ReplicationRole.Role> onRoleChange, Connection natsConn) {
        this.podName      = podName;
        this.podNameBytes = podName.getBytes();
        this.namespace    = namespace;
        this.role         = role;
        this.onRoleChange = onRoleChange;
        this.natsConn     = natsConn;

        this.leaseDurationSeconds = (int) envLong("LEASE_DURATION_SECONDS", 15);
        this.renewDeadlineNs      = envLong("RENEW_DEADLINE_SECONDS", 10) * 1_000_000_000L;
        this.renewIntervalNs      = envLong("RENEW_INTERVAL_SECONDS", 2) * 1_000_000_000L;
        this.heartbeatTimeoutNs   = envLong("HEARTBEAT_TIMEOUT_MS", 500) * 1_000_000L;
        long httpTimeoutSeconds   = envLong("LEASE_HTTP_TIMEOUT_SECONDS", 2);

        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");
        this.apiBase = (host != null && port != null)
            ? "https://" + host + ":" + port
            : "https://kubernetes.default.svc";
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(httpTimeoutSeconds))
            .sslContext(buildSslContext())
            .build();
        this.token = readToken();
    }

    // ----- telemetry accessors (read by OrderMatcherService.prometheusMetrics + LmaxEngine gate) --

    /** Nanotime of the last confirmed renewal; the admission gate compares this to {@link #renewDeadlineNanos()}. */
    public long lastSuccessfulRenewNs() { return lastSuccessfulRenewNs; }
    public long renewDeadlineNanos()    { return renewDeadlineNs; }
    public long lastRenewLatencyNanos() { return lastRenewLatencyNanos; }
    public long demoteForeignHolderCount() { return demoteForeignHolder.sum(); }
    public long demoteDeadlineCount()      { return demoteDeadline.sum(); }

    /** Try to become primary on startup. Returns true if this pod acquired the Lease. */
    public boolean tryAcquire() {
        String holder = currentHolder();
        if (holder == null) {
            boolean ok = createLease();
            if (ok) lastSuccessfulRenewNs = System.nanoTime();
            return ok;
        }
        if (podName.equals(holder) || isLeaseExpired()) {
            boolean ok = updateLease() == RenewResult.SUCCESS;
            if (ok) lastSuccessfulRenewNs = System.nanoTime();
            return ok;
        }
        log.info("Lease held by {} — starting as FOLLOWER", holder);
        return false;
    }

    /** Start the background threads and set up heartbeat. Idempotent — safe if called twice. */
    public synchronized void start() {
        if (heartbeatScheduler != null) return;   // already started
        if (role.isPrimary()) lastSuccessfulRenewNs = System.nanoTime();
        // Stamp the routing label for the STARTUP role. Historically only the promotion/demotion
        // paths patched blp-role, so a pair that elected cleanly at boot and never flapped had no
        // labels at all — and order-matcher-primary had zero endpoints (masked before the
        // false-demote fix because constant flapping always ran the promotion path).
        patchPodLabel(role.isPrimary() ? "primary" : "standby");
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(daemon("blp-heartbeat"));
        leaseScheduler     = Executors.newSingleThreadScheduledExecutor(daemon("blp-lease"));
        setupHeartbeat();
        heartbeatScheduler.scheduleAtFixedRate(this::heartbeatTick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        long renewMs = Math.max(200, renewIntervalNs / 1_000_000L);
        leaseScheduler.scheduleAtFixedRate(this::leaseTick, renewMs, renewMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (heartbeatScheduler != null) heartbeatScheduler.shutdownNow();
        if (leaseScheduler != null) leaseScheduler.shutdownNow();
        teardownHeartbeat();
    }

    /**
     * A fast-witness winner already owns the synchronous admission fence. Reconcile the
     * Kubernetes Lease off the critical path and switch this instance to primary maintenance.
     */
    public void witnessPromoted() {
        cachedResourceVersion = null;
        lastSuccessfulRenewNs = 0L;
        witnessReconciliationPending = true;
        teardownHeartbeat();
        patchPodLabel("primary");
        ScheduledExecutorService scheduler = leaseScheduler;
        if (scheduler != null) scheduler.execute(this::renewOrDemote);
    }

    // ----- ticks -----------------------------------------------------------------------------------

    /** 100 ms cadence. Primary publishes heartbeat (non-blocking); follower watches for silence. */
    private void heartbeatTick() {
        if (role.isPrimary()) {
            publishHeartbeat();
        } else {
            long now = System.nanoTime();
            if (lastHeartbeatNs > 0 && now - lastHeartbeatNs > heartbeatTimeoutNs) {
                watchAndPromote();
            }
        }
    }

    /** Renew cadence. Primary renews-or-demotes; follower polls the Lease as a backup detection path. */
    private void leaseTick() {
        if (role.isPrimary()) {
            renewOrDemote();
        } else {
            // Backup path (also covers heartbeat never starting, e.g. natsConn == null).
            long now = System.nanoTime();
            boolean heartbeatFresh = lastHeartbeatNs > 0 && now - lastHeartbeatNs <= heartbeatTimeoutNs;
            if (!heartbeatFresh && isLeaseExpired()) watchAndPromote();
        }
    }

    // ----- primary: renew Lease + publish heartbeat -----------------------------------------------

    private void publishHeartbeat() {
        if (natsConn == null) return;
        try {
            natsConn.publish(HEARTBEAT_SUBJECT, podNameBytes);
        } catch (Exception ex) {
            // non-fatal; follower will detect stale after heartbeatTimeoutNs
        }
    }

    private void renewOrDemote() {
        RenewResult r;
        try {
            r = updateLease();
        } catch (Exception ex) {
            r = RenewResult.AMBIGUOUS;
            log.warn("Lease renew error: {}", ex.getMessage());
        }
        switch (r) {
            case SUCCESS -> {
                lastSuccessfulRenewNs = System.nanoTime();
                witnessReconciliationPending = false;
            }
            case FOREIGN_HOLDER -> {
                if (witnessReconciliationPending) {
                    log.warn("Lease still names the previous holder after witness promotion — retrying asynchronously");
                } else {
                    demote("foreign_holder");
                }
            }
            case AMBIGUOUS -> {
                if (witnessReconciliationPending) {
                    log.warn("Lease reconciliation remains ambiguous after witness promotion — retrying asynchronously");
                } else {
                    long age = System.nanoTime() - lastSuccessfulRenewNs;
                    if (age > renewDeadlineNs) {
                        demote("deadline");
                    } else {
                        log.warn("Lease renewal unconfirmed for {} ms (deadline {} ms) — retrying, not demoting",
                            age / 1_000_000, renewDeadlineNs / 1_000_000);
                    }
                }
            }
        }
    }

    private void demote(String cause) {
        log.warn("Demoting to FOLLOWER (cause={})", cause);
        if ("foreign_holder".equals(cause)) demoteForeignHolder.increment(); else demoteDeadline.increment();
        cachedResourceVersion = null;
        role.set(ReplicationRole.Role.FOLLOWER);
        onRoleChange.accept(ReplicationRole.Role.FOLLOWER);
        patchPodLabel("standby");
        subscribeToHeartbeat();   // switch from publishing to watching
    }

    // ----- follower: detect + promote -------------------------------------------------------------

    private void watchAndPromote() {
        try {
            String holder = currentHolder();
            if (holder == null) {                 // no Lease object → acquire if expired/absent
                if (isLeaseExpired()) attemptPromotion();
                return;
            }
            if (podName.equals(holder)) return;   // we already hold it

            PodStatus ps = podStatus(holder);
            if (ps == PodStatus.GONE) {           // process provably gone → safe immediate steal
                terminatingHolder = null;
                log.info("Holder pod {} is gone — fast promotion of {}", holder, podName);
                attemptPromotion();
                return;
            }
            if (ps == PodStatus.TERMINATING) {
                long now = System.nanoTime();
                if (!holder.equals(terminatingHolder)) { terminatingHolder = holder; terminatingSinceNs = now; }
                if (now - terminatingSinceNs > TERMINATING_GUARD_NS) {
                    log.info("Holder pod {} terminating past guard — promoting {}", holder, podName);
                    attemptPromotion();
                }
                return;
            }
            terminatingHolder = null;
            if (isLeaseExpired()) attemptPromotion();   // holder alive but lease expired
        } catch (Exception ex) {
            log.warn("Promote check error: {}", ex.getMessage());
        }
    }

    private synchronized void attemptPromotion() {
        // The heartbeat and Lease schedulers can discover the same failure concurrently. The
        // second path must not run another role transition after the first path has won.
        if (role.isPrimary()) return;
        if (updateLease() == RenewResult.SUCCESS) {
            lastSuccessfulRenewNs = System.nanoTime();
            log.info("Promoted {} to PRIMARY", podName);
            role.set(ReplicationRole.Role.PRIMARY);
            onRoleChange.accept(ReplicationRole.Role.PRIMARY);
            if (role.isPrimary()) {
                patchPodLabel("primary");
                teardownHeartbeat();   // switch from watching to publishing
            } else {
                patchPodLabel("standby");
                subscribeToHeartbeat();
            }
        } else {
            log.warn("Lease theft failed — another pod may have won; backing off");
        }
    }

    // ----- heartbeat management -------------------------------------------------------------------

    private void setupHeartbeat() {
        if (!role.isPrimary()) subscribeToHeartbeat();
    }

    private void subscribeToHeartbeat() {
        if (natsConn == null) return;
        teardownHeartbeat();
        try {
            lastHeartbeatNs = 0; // reset so we don't trigger immediately on a stale value
            heartbeatDispatcher = natsConn.createDispatcher(msg -> lastHeartbeatNs = System.nanoTime());
            heartbeatDispatcher.subscribe(HEARTBEAT_SUBJECT);
            log.info("Follower heartbeat watcher active on {}", HEARTBEAT_SUBJECT);
        } catch (Exception ex) {
            log.warn("Could not subscribe to heartbeat — relying on Lease poll for detection: {}", ex.getMessage());
        }
    }

    private void teardownHeartbeat() {
        Dispatcher d = heartbeatDispatcher;
        if (d != null) {
            try { d.unsubscribe(HEARTBEAT_SUBJECT); } catch (Exception ignore) {}
            heartbeatDispatcher = null;
        }
    }

    // ----- Kubernetes API -------------------------------------------------------------------------

    private String currentHolder() {
        try {
            HttpResponse<String> resp = http.send(
                req("GET", leasePath()).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return null;
            return extractJsonString(resp.body(), "holderIdentity");
        } catch (Exception ex) {
            log.warn("Could not read Lease holder: {}", ex.getMessage());
            return null;
        }
    }

    /** Fetches the current Lease as [renewTime, resourceVersion, holderIdentity], or null on 404. */
    private String[] fetchLease() {
        try {
            HttpResponse<String> resp = http.send(
                req("GET", leasePath()).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return null;
            String body = resp.body();
            return new String[]{
                extractJsonString(body, "renewTime"),
                extractJsonString(body, "resourceVersion"),
                extractJsonString(body, "holderIdentity")
            };
        } catch (Exception ex) {
            log.warn("Could not read Lease: {}", ex.getMessage());
            return new String[]{null, null, null};
        }
    }

    private boolean isLeaseExpired() {
        String[] lease = fetchLease();
        if (lease == null || lease[0] == null) return true;   // 404 / no renewTime → expired
        try {
            Instant last = Instant.parse(lease[0]);
            return Instant.now().isAfter(last.plusSeconds(leaseDurationSeconds));
        } catch (Exception ex) {
            log.warn("Could not parse Lease renewTime '{}': {}", lease[0], ex.getMessage());
            return true;
        }
    }

    private PodStatus podStatus(String pod) {
        try {
            HttpResponse<String> resp = http.send(
                req("GET", "/api/v1/namespaces/" + namespace + "/pods/" + pod).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return PodStatus.GONE;
            if (resp.statusCode() != 200) return PodStatus.UNKNOWN;
            // A real deletion stamps a quoted timestamp value; `"deletionTimestamp":null` is not terminating.
            return resp.body().contains("\"deletionTimestamp\":\"") ? PodStatus.TERMINATING : PodStatus.ALIVE;
        } catch (Exception ex) {
            return PodStatus.UNKNOWN;
        }
    }

    private boolean createLease() {
        try {
            HttpResponse<String> resp = http.send(
                req("POST", "/apis/coordination.k8s.io/v1/namespaces/" + namespace + "/leases")
                    .POST(HttpRequest.BodyPublishers.ofString(leaseBody(null)))
                    .header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
            log.info("Create Lease {}: status={}", LEASE_NAME, resp.statusCode());
            if (resp.statusCode() == 201) {
                cachedResourceVersion = extractJsonString(resp.body(), "resourceVersion");
                return true;
            }
            return false;
        } catch (Exception ex) {
            log.warn("Create Lease failed: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Optimistic single-PUT renewal against the cached resourceVersion. On 409, resync once via GET:
     * if we are still the holder (our prior timed-out PUT actually landed) refresh + retry; if a
     * foreign pod holds it, that is confirmed loss. Timeout/5xx/other → AMBIGUOUS (caller retries
     * until the renew deadline, never an immediate demote).
     */
    private RenewResult updateLease() {
        long t0 = System.nanoTime();
        try {
            if (cachedResourceVersion == null) {
                String[] lease = fetchLease();
                cachedResourceVersion = (lease != null) ? lease[1] : null;
            }
            HttpResponse<String> resp = putLease(cachedResourceVersion);
            int sc = resp.statusCode();
            if (sc == 200 || sc == 201) {
                cachedResourceVersion = extractJsonString(resp.body(), "resourceVersion");
                return RenewResult.SUCCESS;
            }
            if (sc == 409) {
                String[] lease = fetchLease();
                if (lease != null) cachedResourceVersion = lease[1];
                if (lease != null && podName.equals(lease[2])) {
                    HttpResponse<String> r2 = putLease(cachedResourceVersion);
                    if (r2.statusCode() == 200 || r2.statusCode() == 201) {
                        cachedResourceVersion = extractJsonString(r2.body(), "resourceVersion");
                        return RenewResult.SUCCESS;
                    }
                    return RenewResult.AMBIGUOUS;
                }
                return RenewResult.FOREIGN_HOLDER;   // someone else holds it
            }
            log.warn("Update Lease status={}", sc);
            return RenewResult.AMBIGUOUS;
        } catch (Exception ex) {
            log.warn("Update Lease failed: {}", ex.getMessage());
            return RenewResult.AMBIGUOUS;
        } finally {
            lastRenewLatencyNanos = System.nanoTime() - t0;
        }
    }

    private HttpResponse<String> putLease(String resourceVersion) throws Exception {
        return http.send(
            req("PUT", leasePath())
                .PUT(HttpRequest.BodyPublishers.ofString(leaseBody(resourceVersion)))
                .header("Content-Type", "application/json").build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private void patchPodLabel(String blpRole) {
        try {
            String patch = "{\"metadata\":{\"labels\":{\"blp-role\":\"" + blpRole + "\"}}}";
            HttpResponse<String> resp = http.send(
                req("PATCH", "/api/v1/namespaces/" + namespace + "/pods/" + podName)
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(patch))
                    .header("Content-Type", "application/strategic-merge-patch+json").build(),
                HttpResponse.BodyHandlers.ofString());
            log.info("Patched pod label blp-role={} status={}", blpRole, resp.statusCode());
        } catch (Exception ex) {
            log.warn("Pod label patch failed: {}", ex.getMessage());
        }
    }

    private String leasePath() {
        return "/apis/coordination.k8s.io/v1/namespaces/" + namespace + "/leases/" + LEASE_NAME;
    }

    private String leaseBody(String resourceVersion) {
        // Kubernetes MicroTime fields require microsecond precision (6 decimals). Instant.toString()
        // produces nanoseconds (9 places) which the API rejects with 400.
        String now = Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
        String rvField = (resourceVersion != null)
            ? ",\n                \"resourceVersion\": \"" + resourceVersion + "\""
            : "";
        return """
            {
              "apiVersion": "coordination.k8s.io/v1",
              "kind": "Lease",
              "metadata": {
                "name": "%s",
                "namespace": "%s"%s
              },
              "spec": {
                "holderIdentity": "%s",
                "leaseDurationSeconds": %d,
                "acquireTime": "%s",
                "renewTime": "%s"
              }
            }
            """.formatted(LEASE_NAME, namespace, rvField, podName, leaseDurationSeconds, now, now);
    }

    private HttpRequest.Builder req(String method, String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create(apiBase + path))
            .timeout(http.connectTimeout().orElse(Duration.ofSeconds(2)))
            .header("Authorization", "Bearer " + token);
    }

    // ----- helpers --------------------------------------------------------------------------------

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; };
    }

    private static long envLong(String key, long dflt) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return dflt;
        try { return Long.parseLong(v.trim()); } catch (Exception ex) { return dflt; }
    }

    private static String readToken() {
        try {
            return Files.readString(Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token")).strip();
        } catch (Exception ex) {
            log.warn("Could not read service account token: {}", ex.getMessage());
            return "";
        }
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static javax.net.ssl.SSLContext buildSslContext() {
        try {
            Path caPath = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt");
            if (!Files.exists(caPath)) {
                return javax.net.ssl.SSLContext.getDefault();
            }
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            java.io.InputStream caStream = Files.newInputStream(caPath);
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            int i = 0;
            for (java.security.cert.Certificate cert : cf.generateCertificates(caStream)) {
                ks.setCertificateEntry("k8s-ca-" + i++, cert);
            }
            javax.net.ssl.TrustManagerFactory tmf =
                javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception ex) {
            log.warn("Could not build k8s SSL context, using JVM default: {}", ex.getMessage());
            try {
                return javax.net.ssl.SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException e2) {
                throw new RuntimeException(e2);
            }
        }
    }
}
