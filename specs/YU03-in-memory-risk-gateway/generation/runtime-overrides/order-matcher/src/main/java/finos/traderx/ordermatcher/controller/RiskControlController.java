package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.risk.GatewayReplicaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Risk control-plane surface (in-memory-risk-gateway, FR-IMRG30/31): authenticated, operator-
 * attributed, versioned control administration. Every mutation is applied to the local Gateway
 * replica AND sequenced into the journaled input stream (ADR-020), so the authoritative BLP
 * decision state replays identically. Never called on the per-command validation path.
 *
 * <p>Slice-1 auth is a shared token + operator header (the real OIDC/entitlement tier is the
 * "real auth" roadmap item); provenance is logged for audit.
 */
@RestController
@RequestMapping("/risk/control")
public final class RiskControlController {
    private static final Logger log = LoggerFactory.getLogger(RiskControlController.class);

    public record AccountDelta(int accountId, boolean enabled) {}
    public record SecurityDelta(String ticker, boolean enabled, boolean halted) {}
    public record PolicyDelta(long policyVersion, boolean killSwitch,
                              Integer maxPositionQuantity, Long maxConcentrationNotionalTicks) {}
    public record RestrictionDelta(String ticker, boolean restricted) {}

    private final GatewayReplicaStore replicas;
    private final LmaxEngine engine;
    private final String controlToken;

    public RiskControlController(GatewayReplicaStore replicas, LmaxEngine engine,
                                 @Value("${risk.control.token:dev-risk-control}") String controlToken) {
        this.replicas = replicas;
        this.engine = engine;
        this.controlToken = controlToken;
    }

    @GetMapping("/snapshot")
    public GatewayReplicaStore.Snapshot snapshot() {
        return replicas.snapshot();
    }

    @PostMapping("/account")
    public ResponseEntity<Void> account(@RequestHeader("X-Risk-Control-Token") String token,
                                        @RequestHeader("X-Risk-Operator") String operator,
                                        @RequestBody AccountDelta delta) {
        authorize(token, operator);
        long version = replicas.applyAccount(delta.accountId(), delta.enabled());
        engine.submitAccountControl(delta.accountId(), delta.enabled(), version);
        log.info("risk_control operator={} type=account version={} account={} enabled={}",
            operator, version, delta.accountId(), delta.enabled());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/security")
    public ResponseEntity<Void> security(@RequestHeader("X-Risk-Control-Token") String token,
                                         @RequestHeader("X-Risk-Operator") String operator,
                                         @RequestBody SecurityDelta delta) {
        authorize(token, operator);
        boolean tradable = delta.enabled() && !delta.halted();
        long version = replicas.applySecurity(delta.ticker(), delta.enabled(), delta.halted());
        engine.submitSecurityControl(delta.ticker(), tradable, version);
        log.info("risk_control operator={} type=security version={} ticker={} enabled={} halted={}",
            operator, version, delta.ticker(), delta.enabled(), delta.halted());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/policy")
    public ResponseEntity<Void> policy(@RequestHeader("X-Risk-Control-Token") String token,
                                       @RequestHeader("X-Risk-Operator") String operator,
                                       @RequestBody PolicyDelta delta) {
        authorize(token, operator);
        replicas.applyPolicy(delta.policyVersion(), delta.killSwitch());
        if (delta.maxPositionQuantity() != null && delta.maxConcentrationNotionalTicks() != null) {
            engine.submitPolicyControl(delta.killSwitch(), delta.policyVersion(),
                delta.maxPositionQuantity(), delta.maxConcentrationNotionalTicks());
        } else {
            engine.submitPolicyControl(delta.killSwitch(), delta.policyVersion());
        }
        log.info("risk_control operator={} type=policy policyVersion={} killSwitch={} maxPos={} maxConc={}",
            operator, delta.policyVersion(), delta.killSwitch(), delta.maxPositionQuantity(),
            delta.maxConcentrationNotionalTicks());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/restriction")
    public ResponseEntity<Void> restriction(@RequestHeader("X-Risk-Control-Token") String token,
                                            @RequestHeader("X-Risk-Operator") String operator,
                                            @RequestBody RestrictionDelta delta) {
        authorize(token, operator);
        long version = replicas.applyRestriction(delta.ticker(), delta.restricted());
        engine.submitRestrictionControl(delta.ticker(), delta.restricted(), version);
        // FR-IMRG24: restricting a security cancels its resting orders through explicit
        // sequenced CANCEL events — never a silent book mutation.
        int canceled = delta.restricted() ? engine.cancelOpenOrdersForSecurity(delta.ticker()) : 0;
        log.info("risk_control operator={} type=restriction version={} ticker={} restricted={} canceled={}",
            operator, version, delta.ticker(), delta.restricted(), canceled);
        return ResponseEntity.noContent().build();
    }

    private void authorize(String token, String operator) {
        if (!controlToken.equals(token) || operator == null || operator.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid risk-control credentials");
        }
    }
}
