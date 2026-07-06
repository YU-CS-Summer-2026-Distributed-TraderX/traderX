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
import java.util.function.Consumer;

/**
 * Kubernetes Lease-based leader election for the BLP StatefulSet, augmented with a NATS
 * heartbeat for fast failure detection.
 *
 * <h3>Detection strategy (two layers)</h3>
 * <ol>
 *   <li><b>NATS heartbeat</b> (fast path): PRIMARY publishes to {@code traderx.blp.heartbeat}
 *       every 100 ms. FOLLOWER considers the primary dead if no heartbeat arrives within
 *       {@link #HEARTBEAT_TIMEOUT_NS} (500 ms) and immediately attempts Lease theft.
 *       Typical failover detection: ~500 ms.
 *   <li><b>Kubernetes Lease</b> (safe path, split-brain guard): {@code leaseDurationSeconds=5},
 *       renewed every 2 s. Even if heartbeat is blocked, the follower detects expiry within 5 s.
 *       The Lease PUT is the atomic promotion gate — only one pod wins the optimistic-concurrency
 *       check, so split-brain is impossible regardless of heartbeat reliability.
 * </ol>
 *
 * <h3>End-to-end failover latency</h3>
 * <ul>
 *   <li>Best case (heartbeat path): ~500 ms (heartbeat timeout) + Lease PUT (~50 ms) +
 *       pod label patch (~50 ms) = <b>~600 ms</b>.
 *   <li>Worst case (heartbeat blocked, Lease path): ~5 s.
 * </ul>
 */
public final class LeaderElection {
    private static final Logger log = LoggerFactory.getLogger(LeaderElection.class);

    private static final String LEASE_NAME          = "order-matcher-leader";
    private static final String HEARTBEAT_SUBJECT   = "traderx.blp.heartbeat";

    // Lease parameters: aggressive but safe for a research deployment.
    private static final int  LEASE_DURATION_SECONDS = 5;
    private static final long RENEW_INTERVAL_NS      = 2_000_000_000L; // 2 s

    // Heartbeat: published every tick (100 ms); stale after 500 ms.
    private static final long TICK_MS               = 100;
    private static final long HEARTBEAT_TIMEOUT_NS  = 500_000_000L;    // 500 ms

    private final String podName;
    private final byte[] podNameBytes;      // cached to avoid getBytes() allocation on every heartbeat
    private final String namespace;
    private final String apiBase;
    private final HttpClient http;
    private final String token;
    private final ReplicationRole role;
    private final Consumer<ReplicationRole.Role> onRoleChange;
    private final Connection natsConn;

    private ScheduledExecutorService scheduler;

    /** Nanotime of last heartbeat received by follower; 0 = none yet. */
    private volatile long lastHeartbeatNs = 0;

    /** Nanotime of next scheduled Lease operation (renew or poll). */
    private volatile long nextLeaseOpNs = 0;

    /** Active NATS Dispatcher for heartbeat subscription (follower only). */
    private volatile Dispatcher heartbeatDispatcher;

    public LeaderElection(String podName, String namespace, ReplicationRole role,
                          Consumer<ReplicationRole.Role> onRoleChange, Connection natsConn) {
        this.podName      = podName;
        this.podNameBytes = podName.getBytes();
        this.namespace    = namespace;
        this.role         = role;
        this.onRoleChange = onRoleChange;
        this.natsConn     = natsConn;
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");
        this.apiBase = (host != null && port != null)
            ? "https://" + host + ":" + port
            : "https://kubernetes.default.svc";
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .sslContext(buildSslContext())
            .build();
        this.token = readToken();
    }

    /** Try to become primary on startup. Returns true if this pod acquired the Lease. */
    public boolean tryAcquire() {
        String holder = currentHolder();
        if (holder == null) {
            return createLease();
        }
        if (podName.equals(holder) || isLeaseExpired()) {
            return updateLease();
        }
        log.info("Lease held by {} — starting as FOLLOWER", holder);
        return false;
    }

    /** Start the background tick and set up heartbeat. */
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "leader-election");
            t.setDaemon(true);
            return t;
        });
        // Do first Lease op on the next tick.
        nextLeaseOpNs = System.nanoTime() + RENEW_INTERVAL_NS;
        setupHeartbeat();
        scheduler.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        teardownHeartbeat();
    }

    // ----- tick ------------------------------------------------------------------------------------

    private void tick() {
        long now = System.nanoTime();
        if (role.isPrimary()) {
            publishHeartbeat();
            if (now >= nextLeaseOpNs) {
                renewOrDemote();
                nextLeaseOpNs = System.nanoTime() + RENEW_INTERVAL_NS;
            }
        } else {
            // Fast path: heartbeat stale → attempt promotion immediately.
            if (lastHeartbeatNs > 0 && now - lastHeartbeatNs > HEARTBEAT_TIMEOUT_NS) {
                log.info("Heartbeat stale ({}ms) — attempting fast promotion",
                    (now - lastHeartbeatNs) / 1_000_000);
                watchAndPromote();
                nextLeaseOpNs = System.nanoTime() + RENEW_INTERVAL_NS;
            } else if (now >= nextLeaseOpNs) {
                // Slow path: periodic Lease poll (also catches cases where heartbeat never started).
                if (isLeaseExpired()) watchAndPromote();
                nextLeaseOpNs = System.nanoTime() + RENEW_INTERVAL_NS;
            }
        }
    }

    // ----- primary: renew Lease + publish heartbeat -----------------------------------------------

    private void publishHeartbeat() {
        if (natsConn == null) return;
        try {
            natsConn.publish(HEARTBEAT_SUBJECT, podNameBytes);
        } catch (Exception ex) {
            // non-fatal; follower will detect stale after HEARTBEAT_TIMEOUT_NS
        }
    }

    private void renewOrDemote() {
        try {
            boolean renewed = updateLease();
            if (!renewed) {
                log.warn("Failed to renew Lease — demoting to FOLLOWER");
                role.set(ReplicationRole.Role.FOLLOWER);
                onRoleChange.accept(ReplicationRole.Role.FOLLOWER);
                patchPodLabel("standby");
                // Switch from publishing heartbeats to watching for them.
                subscribeToHeartbeat();
            }
        } catch (Exception ex) {
            log.warn("Lease renew error: {}", ex.getMessage());
        }
    }

    // ----- follower: detect expiry + promote ------------------------------------------------------

    private void watchAndPromote() {
        try {
            if (!isLeaseExpired()) return;
            log.info("Primary detected as lost — promoting {} to PRIMARY", podName);
            if (updateLease()) {
                role.set(ReplicationRole.Role.PRIMARY);
                onRoleChange.accept(ReplicationRole.Role.PRIMARY);
                patchPodLabel("primary");
                // Switch from watching heartbeats to publishing them.
                teardownHeartbeat();
            } else {
                log.warn("Lease theft failed — another pod may have won; backing off");
            }
        } catch (Exception ex) {
            log.warn("Promote check error: {}", ex.getMessage());
        }
    }

    // ----- heartbeat management -------------------------------------------------------------------

    private void setupHeartbeat() {
        if (role.isPrimary()) {
            // heartbeat is published in tick(); no subscription needed
        } else {
            subscribeToHeartbeat();
        }
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
            HttpRequest req = req("GET",
                "/apis/coordination.k8s.io/v1/namespaces/" + namespace + "/leases/" + LEASE_NAME)
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return null;
            return extractJsonString(resp.body(), "holderIdentity");
        } catch (Exception ex) {
            log.warn("Could not read Lease holder: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Fetches the current Lease and returns [renewTime, resourceVersion], or null on 404.
     * Both values are needed together: renewTime for expiry detection and resourceVersion for PUT.
     */
    private String[] fetchLease() {
        try {
            HttpRequest req = req("GET",
                "/apis/coordination.k8s.io/v1/namespaces/" + namespace + "/leases/" + LEASE_NAME)
                .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return null;
            String body = resp.body();
            String renewTime       = extractJsonString(body, "renewTime");
            String resourceVersion = extractJsonString(body, "resourceVersion");
            return new String[]{renewTime, resourceVersion};
        } catch (Exception ex) {
            log.warn("Could not read Lease: {}", ex.getMessage());
            return new String[]{null, null};
        }
    }

    private boolean isLeaseExpired() {
        String[] lease = fetchLease();
        if (lease == null) return true;             // 404 → treat as expired
        String renewTime = lease[0];
        if (renewTime == null) return true;
        try {
            Instant last = Instant.parse(renewTime);
            return Instant.now().isAfter(last.plusSeconds(LEASE_DURATION_SECONDS));
        } catch (Exception ex) {
            log.warn("Could not parse Lease renewTime '{}': {}", renewTime, ex.getMessage());
            return true;
        }
    }

    private boolean createLease() {
        try {
            String body = leaseBody(null);
            HttpRequest req = req("POST",
                "/apis/coordination.k8s.io/v1/namespaces/" + namespace + "/leases")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() == 201;
            log.info("Create Lease {}: status={}", LEASE_NAME, resp.statusCode());
            return ok;
        } catch (Exception ex) {
            log.warn("Create Lease failed: {}", ex.getMessage());
            return false;
        }
    }

    private boolean updateLease() {
        try {
            String[] lease = fetchLease();
            // resourceVersion is required by k8s for PUT conflict detection (optimistic concurrency).
            // null means the Lease was deleted mid-flight — treat as lost.
            String resourceVersion = (lease != null) ? lease[1] : null;
            if (lease != null && resourceVersion == null) {
                log.warn("Lease exists but has no resourceVersion — skipping update");
                return false;
            }
            String body = leaseBody(resourceVersion);
            HttpRequest req = req("PUT",
                "/apis/coordination.k8s.io/v1/namespaces/" + namespace + "/leases/" + LEASE_NAME)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                log.warn("Update Lease returned status={}: {}", resp.statusCode(),
                    resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
                return false;
            }
            return true;
        } catch (Exception ex) {
            log.warn("Update Lease failed: {}", ex.getMessage());
            return false;
        }
    }

    private void patchPodLabel(String blpRole) {
        try {
            String patch = "{\"metadata\":{\"labels\":{\"blp-role\":\"" + blpRole + "\"}}}";
            HttpRequest req = req("PATCH",
                "/api/v1/namespaces/" + namespace + "/pods/" + podName)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(patch))
                .header("Content-Type", "application/strategic-merge-patch+json")
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("Patched pod label blp-role={} status={}", blpRole, resp.statusCode());
        } catch (Exception ex) {
            log.warn("Pod label patch failed: {}", ex.getMessage());
        }
    }

    private String leaseBody(String resourceVersion) {
        // Kubernetes MicroTime fields require microsecond precision (6 decimal places).
        // Instant.toString() produces nanoseconds (9 places) which the API rejects with 400.
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
            """.formatted(LEASE_NAME, namespace, rvField, podName, LEASE_DURATION_SECONDS, now, now);
    }

    private HttpRequest.Builder req(String method, String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create(apiBase + path))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token);
    }

    // ----- helpers --------------------------------------------------------------------------------

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
