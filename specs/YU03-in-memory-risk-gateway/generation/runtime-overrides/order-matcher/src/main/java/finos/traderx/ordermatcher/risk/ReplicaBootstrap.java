package finos.traderx.ordermatcher.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.lmax.ReplicationRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Startup bootstrap of the Gateway replica's account/security universe (in-memory-risk-gateway,
 * the slice-1 stand-in for the durable account-service / reference-data control feeds of
 * FR-IMRG32/33). One cold snapshot fetch per source at startup — never on the per-command
 * admission path (FR-IMRG01) — and every fetched record is SEQUENCED through the journaled
 * control stream (ADR-020), so the BLP's authoritative control state is reproduced by replay,
 * not by re-querying mutable external services (FR-IMRG22).
 *
 * <p>The replica is already ready on its configuration seeds (a complete known-good initial
 * image installed by LmaxEngine); this bootstrap ENRICHES the tradable universe, and names
 * outside the installed image fail closed as UNKNOWN_SECURITY/UNKNOWN_ACCOUNT until admitted.
 * Retries forever with backoff; a FOLLOWER never bootstraps (its control state arrives via
 * replication) but a promoted follower picks the loop up.
 */
@Component
public final class ReplicaBootstrap {
    private static final Logger log = LoggerFactory.getLogger(ReplicaBootstrap.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final long RETRY_BACKOFF_MS = 5_000L;

    private final GatewayReplicaStore replicas;
    private final LmaxEngine engine;
    private final ReplicationRole replicationRole;
    private final boolean riskEnabled;
    private final boolean bootstrapEnabled;
    private final String accountsUrl;
    private final String securitiesUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean stopped;

    public ReplicaBootstrap(GatewayReplicaStore replicas, LmaxEngine engine,
                            ReplicationRole replicationRole,
                            @Value("${risk.enabled:true}") boolean riskEnabled,
                            @Value("${risk.bootstrap.enabled:true}") boolean bootstrapEnabled,
                            @Value("${risk.bootstrap.accounts-url:http://account-service:18088/account/}") String accountsUrl,
                            @Value("${risk.bootstrap.securities-url:http://reference-data:18085/stocks}") String securitiesUrl) {
        this.replicas = replicas;
        this.engine = engine;
        this.replicationRole = replicationRole;
        this.riskEnabled = riskEnabled;
        this.bootstrapEnabled = bootstrapEnabled;
        this.accountsUrl = accountsUrl;
        this.securitiesUrl = securitiesUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!riskEnabled) {
            return;
        }
        if (!bootstrapEnabled) {
            log.info("Risk replica bootstrap disabled: running on configuration seeds only");
            return;
        }
        Thread worker = new Thread(this::run, "risk-replica-bootstrap");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        while (!stopped) {
            try {
                if (!engine.recoveryReady() || !replicationRole.isPrimary()) {
                    Thread.sleep(1_000L);
                    continue;
                }
                bootstrapOnce();
                log.info("Risk replica bootstrap complete: accounts={} securities={} (version {})",
                    replicas.accountCount(), replicas.securityCount(), replicas.sourceVersion());
                return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.warn("Risk replica bootstrap failed (admission stays closed; retrying in {} ms): {}",
                    RETRY_BACKOFF_MS, ex.toString());
                try {
                    Thread.sleep(RETRY_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Fetch both source snapshots, then admit each record through the versioned replica apply
     *  AND the journaled control stream, so replay reproduces the same admission state. */
    private void bootstrapOnce() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        JsonNode accounts = fetch(client, accountsUrl);
        JsonNode securities = fetch(client, securitiesUrl);

        for (JsonNode account : accounts) {
            JsonNode id = account.has("id") ? account.get("id") : account.get("accountId");
            if (id == null || !id.canConvertToInt()) {
                continue;
            }
            long version = replicas.applyAccount(id.asInt(), true);
            engine.submitAccountControl(id.asInt(), true, version);
        }
        for (JsonNode stock : securities) {
            JsonNode ticker = stock.get("ticker");
            if (ticker == null || ticker.asText().isBlank()) {
                continue;
            }
            long version = replicas.applySecurity(ticker.asText(), true, false);
            engine.submitSecurityControl(ticker.asText(), true, version);
        }
    }

    private JsonNode fetch(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(HTTP_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("bootstrap fetch " + url + " -> HTTP " + response.statusCode());
        }
        JsonNode body = mapper.readTree(response.body());
        if (!body.isArray()) {
            throw new IllegalStateException("bootstrap fetch " + url + " -> non-array payload");
        }
        return body;
    }

    public void stop() {
        stopped = true;
    }
}
