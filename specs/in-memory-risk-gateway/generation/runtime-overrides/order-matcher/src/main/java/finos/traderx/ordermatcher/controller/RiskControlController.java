package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.risk.GatewayReplicaStore;
import finos.traderx.ordermatcher.lmax.LmaxEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Demo control-plane surface; never called by the per-command validation path. */
@RestController
@RequestMapping("/risk/control")
public final class RiskControlController {
    private static final Logger log = LoggerFactory.getLogger(RiskControlController.class);
    public record AccountDelta(long epoch, long version, int accountId, boolean enabled) {}
    public record SecurityDelta(long epoch, long version, String ticker, boolean enabled, boolean halted) {}
    public record PolicyDelta(long epoch, long version, long policyVersion, boolean killSwitch) {}
    public record RestrictionDelta(long version, String ticker, boolean restricted) {}

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
        replicas.applyAccount(delta.epoch(), delta.version(), delta.accountId(), delta.enabled());
        engine.submitAccountControl(delta.accountId(), delta.enabled(), delta.version());
        log.info("risk_control operator={} type=account version={} account={} enabled={}",
            operator, delta.version(), delta.accountId(), delta.enabled());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/security")
    public ResponseEntity<Void> security(@RequestHeader("X-Risk-Control-Token") String token,
                                         @RequestHeader("X-Risk-Operator") String operator,
                                         @RequestBody SecurityDelta delta) {
        authorize(token, operator);
        replicas.applySecurity(delta.epoch(), delta.version(), delta.ticker(), delta.enabled(), delta.halted());
        engine.submitSecurityControl(delta.ticker(), delta.enabled() && !delta.halted(), delta.version());
        log.info("risk_control operator={} type=security version={} ticker={} enabled={} halted={}",
            operator, delta.version(), delta.ticker(), delta.enabled(), delta.halted());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/policy")
    public ResponseEntity<Void> policy(@RequestHeader("X-Risk-Control-Token") String token,
                                       @RequestHeader("X-Risk-Operator") String operator,
                                       @RequestBody PolicyDelta delta) {
        authorize(token, operator);
        replicas.applyPolicy(delta.epoch(), delta.version(), delta.policyVersion(), delta.killSwitch());
        engine.submitPolicyControl(delta.killSwitch(), delta.policyVersion());
        log.info("risk_control operator={} type=policy version={} policyVersion={} killSwitch={}",
            operator, delta.version(), delta.policyVersion(), delta.killSwitch());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/restriction")
    public ResponseEntity<Void> restriction(@RequestHeader("X-Risk-Control-Token") String token,
                                            @RequestHeader("X-Risk-Operator") String operator,
                                            @RequestBody RestrictionDelta delta) {
        authorize(token, operator);
        replicas.applyRestriction(delta.ticker(), delta.restricted());
        engine.submitRestrictionControl(delta.ticker(), delta.restricted(), delta.version());
        int canceled = delta.restricted() ? engine.cancelOpenOrdersForSecurity(delta.ticker()) : 0;
        log.info("risk_control operator={} type=restriction version={} ticker={} restricted={}",
            operator, delta.version(), delta.ticker(), delta.restricted());
        log.info("risk_control operator={} type=restriction_cancel ticker={} canceled={}",
            operator, delta.ticker(), canceled);
        return ResponseEntity.noContent().build();
    }

    private void authorize(String token, String operator) {
        if (!controlToken.equals(token) || operator == null || operator.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid risk-control credentials");
        }
    }
}
