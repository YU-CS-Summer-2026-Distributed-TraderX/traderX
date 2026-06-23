package finos.traderx.ordermatcher.risk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.ordermatcher.lmax.LmaxEngine;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Durable control consumer. The pull consumer is created before snapshots are fetched, so deltas
 * racing the HTTP snapshot remain retained. Snapshot watermark W is installed atomically and the
 * consumer discards <=W, applies contiguous >W, and invalidates readiness on a gap or epoch change.
 */
@Component
public final class ControlStreamConsumer implements InitializingBean, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(ControlStreamConsumer.class);
    private static final String STREAM = "TRADERX_RISK_CONTROL";
    private static final String DURABLE = "order-matcher-risk-control";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountImage(int accountId, boolean enabled, long version) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountSnapshot(long sourceEpoch, long watermark, long highWatermark,
                                  List<AccountImage> accounts, List<EntitlementImage> entitlements) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EntitlementImage(String principal, int accountId, boolean enabled, long version) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SecurityImage(int securityId, String ticker, boolean enabled, boolean halted,
                                long version) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SecuritySnapshot(long sourceEpoch, long watermark, long highWatermark,
                                   List<SecurityImage> securities) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountDelta(long version, long sourceEpoch, String eventType, int accountId,
                               String principal, boolean enabled, long sourceTimeMillis) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SecurityDelta(long version, long sourceEpoch, int securityId, String ticker,
                                boolean enabled, boolean halted, long sourceTimeMillis) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiskControlDelta(long version, long sourceEpoch, String eventType, String aggregateKey,
                                   boolean enabled, long policyVersion, int maxPositionQuantity,
                                   long maxConcentrationNotionalTicks, String operator,
                                   long sourceTimeMillis) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiskControlSnapshot(long sourceEpoch, long watermark, long highWatermark,
                                      List<RiskControlDelta> controls) {}

    private final GatewayReplicaStore replicas;
    private final LmaxEngine engine;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String natsAddress;
    private final URI accountSnapshotUri;
    private final URI securitySnapshotUri;
    private final URI riskSnapshotUri;
    private final long bootstrapTimeoutMillis;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile boolean running;
    private Connection connection;
    private JetStreamSubscription subscription;
    private Thread worker;

    public ControlStreamConsumer(GatewayReplicaStore replicas, LmaxEngine engine, ObjectMapper json,
        @Value("${risk.control-stream.enabled:false}") boolean enabled,
        @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress,
        @Value("${risk.account-snapshot-url:http://localhost:18088/account/control/snapshot}") String accountSnapshotUrl,
        @Value("${risk.security-snapshot-url:http://localhost:18085/stocks/control/snapshot}") String securitySnapshotUrl,
        @Value("${risk.policy-snapshot-url:http://localhost:18088/risk-admin/control/snapshot}") String riskSnapshotUrl,
        @Value("${risk.replica.bootstrap.timeout-ms:30000}") long bootstrapTimeoutMillis) {
        this.replicas = replicas;
        this.engine = engine;
        this.json = json;
        this.enabled = enabled;
        this.natsAddress = natsAddress;
        this.accountSnapshotUri = URI.create(accountSnapshotUrl);
        this.securitySnapshotUri = URI.create(securitySnapshotUrl);
        this.riskSnapshotUri = URI.create(riskSnapshotUrl);
        this.bootstrapTimeoutMillis = bootstrapTimeoutMillis;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!enabled) return;
        replicas.beginExternalBootstrap();
        connection = Nats.connect(new Options.Builder().server(natsAddress)
            .connectionTimeout(Duration.ofSeconds(3)).maxReconnects(-1).build());
        ensureStream(connection.jetStreamManagement());
        JetStream jetStream = connection.jetStream();
        subscription = jetStream.subscribe("traderx.control.>", PullSubscribeOptions.builder()
            .stream(STREAM).durable(DURABLE).build());
        installSnapshotsWithRetry();
        replicas.markControlConnected(System.currentTimeMillis());
        running = true;
        worker = Thread.ofPlatform().daemon(true).name("risk-control-consumer").start(this::consume);
        log.info("Risk control consumer ready={} accountVersion={} securityVersion={}", replicas.ready(),
            replicas.accountVersion(), replicas.securityVersion());
    }

    private void installSnapshotsWithRetry() throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(bootstrapTimeoutMillis).toNanos();
        Exception last = null;
        do {
            try {
                installSnapshots();
                return;
            } catch (Exception unavailable) {
                last = unavailable;
                log.warn("Risk snapshot source not ready; bootstrap retrying: {}", unavailable.toString());
                Thread.sleep(250L);
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("risk replica bootstrap timed out after " + bootstrapTimeoutMillis
            + "ms", last);
    }

    private void ensureStream(JetStreamManagement management) throws Exception {
        try {
            management.getStreamInfo(STREAM);
        } catch (Exception missing) {
            StreamConfiguration config = StreamConfiguration.builder().name(STREAM)
                .subjects("traderx.control.account.*", "traderx.control.entitlement.*",
                    "traderx.control.security.*",
                    "traderx.control.risk-policy.*", "traderx.control.restriction.*",
                    "traderx.control.kill-switch.*")
                .storageType(StorageType.File).retentionPolicy(RetentionPolicy.Limits)
                .maxAge(Duration.ofDays(7)).maxMessages(1_000_000).build();
            management.addStream(config);
        }
    }

    private void installSnapshots() throws Exception {
        AccountSnapshot account = get(accountSnapshotUri, AccountSnapshot.class);
        SecuritySnapshot security = get(securitySnapshotUri, SecuritySnapshot.class);
        RiskControlSnapshot risk = get(riskSnapshotUri, RiskControlSnapshot.class);
        List<GatewayReplicaStore.AccountRecord> accountImage = account.accounts().stream()
            .map(item -> new GatewayReplicaStore.AccountRecord(item.accountId(), item.enabled(), item.version()))
            .toList();
        List<GatewayReplicaStore.SecurityRecord> securityImage = security.securities().stream()
            .map(item -> new GatewayReplicaStore.SecurityRecord(item.securityId(), item.ticker(), item.enabled(),
                item.halted(), Long.MIN_VALUE, 0L, item.version())).toList();
        replicas.installAccountSnapshot(account.sourceEpoch(), account.watermark(), account.highWatermark(),
            accountImage, account.entitlements().stream().map(item -> new GatewayReplicaStore.EntitlementRecord(
                item.principal(), item.accountId(), item.enabled(), item.version())).toList());
        replicas.installSecuritySnapshot(security.sourceEpoch(), security.watermark(), security.highWatermark(),
            securityImage);
        long policyVersion = 1L;
        boolean killSwitch = false;
        Map<String, Boolean> restrictions = new HashMap<>();
        for (RiskControlDelta control : risk.controls()) {
            switch (control.eventType()) {
                case "POLICY" -> policyVersion = control.policyVersion();
                case "KILL_SWITCH" -> killSwitch = control.enabled();
                case "RESTRICTION" -> restrictions.put(control.aggregateKey(), control.enabled());
                default -> throw new IllegalArgumentException("unsupported risk snapshot control "
                    + control.eventType());
            }
        }
        replicas.installRiskSnapshot(risk.sourceEpoch(), risk.watermark(), risk.highWatermark(),
            policyVersion, killSwitch, restrictions);
        for (GatewayReplicaStore.AccountRecord item : accountImage) {
            engine.submitAccountControl(item.accountId(), item.enabled(), item.version());
        }
        for (EntitlementImage item : account.entitlements()) {
            engine.submitEntitlementControl(item.principal(), item.accountId(), item.enabled(), item.version());
        }
        for (GatewayReplicaStore.SecurityRecord item : securityImage) {
            engine.submitSecurityControl(item.ticker(), item.enabled() && !item.halted(), item.version());
        }
        for (RiskControlDelta control : risk.controls()) {
            switch (control.eventType()) {
                case "POLICY" -> engine.submitPolicyControl(false, control.policyVersion(),
                    control.maxPositionQuantity(), control.maxConcentrationNotionalTicks());
                case "KILL_SWITCH" -> engine.submitPolicyControl(control.enabled(),
                    Math.max(1L, control.policyVersion()));
                case "RESTRICTION" -> engine.submitRestrictionControl(control.aggregateKey(),
                    control.enabled(), control.version());
                default -> throw new IllegalArgumentException("unsupported risk snapshot control "
                    + control.eventType());
            }
        }
    }

    private <T> T get(URI uri, Class<T> type) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("snapshot request failed: " + uri + " status=" + response.statusCode());
        }
        return json.readValue(response.body(), type);
    }

    private void consume() {
        while (running) {
            try {
                for (Message message : subscription.fetch(256, Duration.ofMillis(500))) {
                    try {
                        apply(message);
                        message.ack();
                        replicas.markControlActivity(System.currentTimeMillis());
                    } catch (Exception invalid) {
                        // Quarantine poison/gapped input so it cannot create an infinite redelivery loop.
                        // The authoritative snapshot is then re-fetched before readiness can recover.
                        replicas.quarantineControlUpdate();
                        message.ack();
                        throw invalid;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                log.error("Risk control consumer invalidated readiness", failure);
                replicas.metrics().rebootstrap();
                replicas.markControlDisconnected(System.currentTimeMillis());
                if (!running) return;
                try {
                    Thread.sleep(500L);
                    installSnapshots();
                    replicas.markControlConnected(System.currentTimeMillis());
                    log.info("Risk control consumer re-bootstrap completed accountVersion={} securityVersion={} riskVersion={}",
                        replicas.accountVersion(), replicas.securityVersion(), replicas.riskVersion());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception rebootstrapFailure) {
                    log.warn("Risk control re-bootstrap deferred: {}", rebootstrapFailure.toString());
                }
            }
        }
    }

    private void apply(Message message) throws Exception {
        String subject = message.getSubject();
        if (subject.startsWith("traderx.control.account.") || subject.startsWith("traderx.control.entitlement.")) {
            AccountDelta delta = json.readValue(message.getData(), AccountDelta.class);
            if ("ENTITLEMENT".equals(delta.eventType())) {
                if (replicas.applyExternalEntitlement(delta.sourceEpoch(), delta.version(), delta.principal(),
                    delta.accountId(), delta.enabled())) {
                    engine.submitEntitlementControl(delta.principal(), delta.accountId(), delta.enabled(),
                        delta.version());
                }
            } else if (replicas.applyExternalAccount(delta.sourceEpoch(), delta.version(), delta.accountId(),
                    delta.enabled())) {
                engine.submitAccountControl(delta.accountId(), delta.enabled(), delta.version());
            }
        } else if (subject.startsWith("traderx.control.security.")) {
            SecurityDelta delta = json.readValue(message.getData(), SecurityDelta.class);
            if (replicas.applyExternalSecurity(delta.sourceEpoch(), delta.version(), delta.securityId(),
                delta.ticker(), delta.enabled(), delta.halted())) {
                engine.submitSecurityControl(delta.ticker(), delta.enabled() && !delta.halted(),
                    delta.version());
            }
        } else if (subject.startsWith("traderx.control.risk-policy.")
                || subject.startsWith("traderx.control.restriction.")
                || subject.startsWith("traderx.control.kill-switch.")) {
            applyRiskControl(json.readValue(message.getData(), RiskControlDelta.class));
        } else {
            throw new IllegalArgumentException("unsupported risk control subject " + subject);
        }
    }

    private void applyRiskControl(RiskControlDelta control) {
        switch (control.eventType()) {
            case "POLICY" -> {
                if (replicas.applyExternalPolicy(control.sourceEpoch(), control.version(),
                        control.policyVersion())) {
                    engine.submitPolicyControl(false, control.policyVersion(), control.maxPositionQuantity(),
                        control.maxConcentrationNotionalTicks());
                }
            }
            case "KILL_SWITCH" -> {
                if (replicas.applyExternalKillSwitch(control.sourceEpoch(), control.version(), control.enabled())) {
                    engine.submitPolicyControl(control.enabled(), Math.max(1L, control.policyVersion()));
                }
            }
            case "RESTRICTION" -> {
                if (replicas.applyExternalRestriction(control.sourceEpoch(), control.version(),
                        control.aggregateKey(), control.enabled())) {
                    engine.submitRestrictionControl(control.aggregateKey(), control.enabled(), control.version());
                    if (control.enabled()) engine.cancelOpenOrdersForSecurity(control.aggregateKey());
                }
            }
            default -> throw new IllegalArgumentException("unsupported risk control " + control.eventType());
        }
    }

    @Override
    public void destroy() throws Exception {
        running = false;
        replicas.markControlDisconnected(System.currentTimeMillis());
        if (worker != null) worker.interrupt();
        if (subscription != null) subscription.unsubscribe();
        if (connection != null) connection.close();
    }
}
