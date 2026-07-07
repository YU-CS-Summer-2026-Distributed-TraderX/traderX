package finos.traderx.ordermatcher.risk;

import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.lmax.ReplicationRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the two durable control feeds (account, security) that replace YU03's one-shot
 * REST bootstrap, adopting ADR-019's subscribe-buffer-snapshot-catchup protocol (ADR-021 for the
 * source-side outbox mechanism). Each source is an independent {@link ControlFeedSubscriber}; a
 * gap/epoch-mismatch/quarantine on one does not force re-bootstrapping the other (FR-IMRG34 is
 * per-source), but overall Gateway readiness requires BOTH to be caught up (FR-IMRG05) — a partial
 * bootstrap never satisfies readiness.
 *
 * <p>Retries forever with backoff, same discipline as YU03's one-shot fetch; a FOLLOWER never
 * bootstraps (its control state arrives via replication) but a promoted follower picks the loop up.
 */
@Component
public final class ReplicaBootstrap {
    private static final Logger log = LoggerFactory.getLogger(ReplicaBootstrap.class);
    private static final long RETRY_BACKOFF_MS = 5_000L;
    private static final long MONITOR_INTERVAL_MS = 1_000L;

    private final GatewayReplicaStore replicas;
    private final LmaxEngine engine;
    private final ReplicationRole replicationRole;
    private final RiskMetrics metrics;
    private final boolean riskEnabled;
    private final boolean bootstrapEnabled;
    private final ControlFeedSubscriber<AccountDelta> accountFeed;
    private final ControlFeedSubscriber<SecurityDelta> securityFeed;
    private volatile boolean stopped;

    public ReplicaBootstrap(
            GatewayReplicaStore replicas,
            LmaxEngine engine,
            ReplicationRole replicationRole,
            @Value("${risk.enabled:true}") boolean riskEnabled,
            @Value("${risk.bootstrap.enabled:true}") boolean bootstrapEnabled,
            @Value("${risk.bootstrap.account-stream:TRADERX_CONTROL_ACCOUNT}") String accountStream,
            @Value("${risk.bootstrap.account-subject:traderx.control.account.deltas}") String accountSubject,
            @Value("${risk.bootstrap.accounts-snapshot-url:http://account-service:18088/account/control-snapshot}")
                String accountsSnapshotUrl,
            @Value("${risk.bootstrap.security-stream:TRADERX_CONTROL_SECURITY}") String securityStream,
            @Value("${risk.bootstrap.security-subject:traderx.control.security.deltas}") String securitySubject,
            @Value("${risk.bootstrap.securities-snapshot-url:http://reference-data:18085/stocks/control-snapshot}")
                String securitiesSnapshotUrl,
            @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress,
            @Value("${risk.bootstrap.buffer-capacity:8192}") int bufferCapacity) {
        this.replicas = replicas;
        this.engine = engine;
        this.replicationRole = replicationRole;
        this.riskEnabled = riskEnabled;
        this.bootstrapEnabled = bootstrapEnabled;
        this.metrics = replicas.metrics();

        this.accountFeed = new ControlFeedSubscriber<>(
            "account", accountStream, accountSubject, accountsSnapshotUrl, natsAddress, bufferCapacity,
            node -> new AccountDelta(node.get("id").asInt(), node.get("displayName").asText()),
            node -> new AccountDelta(node.get("accountId").asInt(), node.get("displayName").asText()),
            d -> d.accountId() + ":" + d.displayName() + ";",
            (delta, sourceVersion) -> {
                long localVersion = replicas.applyAccount(delta.accountId(), true, sourceVersion);
                engine.submitAccountControl(delta.accountId(), true, localVersion);
            },
            () -> onQuarantine("account"));

        this.securityFeed = new ControlFeedSubscriber<>(
            "security", securityStream, securitySubject, securitiesSnapshotUrl, natsAddress, bufferCapacity,
            node -> new SecurityDelta(node.get("ticker").asText(), node.get("companyName").asText()),
            node -> new SecurityDelta(node.get("ticker").asText(), node.get("companyName").asText()),
            d -> d.ticker() + ":" + d.companyName() + ";",
            (delta, sourceVersion) -> {
                long localVersion = replicas.applySecurity(delta.ticker(), true, false, sourceVersion);
                engine.submitSecurityControl(delta.ticker(), true, localVersion);
            },
            () -> onQuarantine("security"));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!riskEnabled) {
            return;
        }
        if (!bootstrapEnabled) {
            // Seeds-only mode: no durable feed will ever be subscribed, so the seed image installed
            // at ring-start is, by definition, the complete admission image — grant readiness
            // immediately rather than waiting on a bootstrap that will never run (used by hot-path
            // tests that exercise BLP/matching behavior, not the durable-feed bootstrap protocol).
            replicas.markReady();
            log.info("Risk replica bootstrap disabled: running on configuration seeds only");
            return;
        }
        Thread worker = new Thread(this::run, "risk-replica-bootstrap");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        boolean accountBootstrapped = false;
        boolean securityBootstrapped = false;
        while (!stopped) {
            try {
                if (!engine.recoveryReady() || !replicationRole.isPrimary()) {
                    Thread.sleep(MONITOR_INTERVAL_MS);
                    continue;
                }
                if (!accountBootstrapped) {
                    accountFeed.bootstrapOnce();
                    accountBootstrapped = true;
                }
                if (!securityBootstrapped) {
                    securityFeed.bootstrapOnce();
                    securityBootstrapped = true;
                }
                updateReadiness();

                // Both sources caught up: monitor for a quarantine (readiness flips back to false
                // on the affected source only) and re-bootstrap just that source, forever.
                while (!stopped) {
                    Thread.sleep(MONITOR_INTERVAL_MS);
                    if (!accountFeed.isReady()) {
                        accountBootstrapped = false;
                        break;
                    }
                    if (!securityFeed.isReady()) {
                        securityBootstrapped = false;
                        break;
                    }
                    updateReadiness();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.warn("Risk replica bootstrap failed (admission stays closed; retrying in {} ms): {}",
                    RETRY_BACKOFF_MS, ex.toString());
                updateReadiness();
                try {
                    Thread.sleep(RETRY_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** A quarantine fails the Gateway closed immediately (FR-IMRG34) — no polling lag on the safety side. */
    private void onQuarantine(String source) {
        metrics.quarantine(source, "gap_or_epoch_mismatch");
        replicas.markNotReady();
        log.warn("{} control feed quarantined; Gateway readiness revoked pending re-bootstrap", source);
    }

    /** FR-IMRG05: ready only once BOTH sources have installed a snapshot and caught up. */
    private void updateReadiness() {
        metrics.sourceWatermark("account", accountFeed.watermark());
        metrics.sourceWatermark("security", securityFeed.watermark());
        boolean bothReady = accountFeed.isReady() && securityFeed.isReady();
        if (bothReady) {
            replicas.markReady();
            log.info("Risk replica bootstrap complete: accounts={} securities={} "
                    + "(account watermark={}, security watermark={})",
                replicas.accountCount(), replicas.securityCount(), accountFeed.watermark(), securityFeed.watermark());
        } else {
            replicas.markNotReady();
        }
    }

    public void stop() {
        stopped = true;
        accountFeed.stop();
        securityFeed.stop();
    }
}
