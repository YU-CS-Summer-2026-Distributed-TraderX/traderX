package finos.traderx.tradeprocessor.controller;

import finos.traderx.tradeprocessor.service.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * YU05 (post-trade-compliance, contract-delta #4): manual settlement override, gated by the same
 * authenticated-control-plane pattern as order-matcher's `/risk/control/*` and `/recon/*` until
 * real OIDC/entitlements (ADR-025) lands.
 */
@RestController
@RequestMapping("/trades")
public final class SettlementController {
    private static final Logger log = LoggerFactory.getLogger(SettlementController.class);

    private final SettlementService settlementService;
    private final String controlToken;

    public SettlementController(SettlementService settlementService,
                                @Value("${recon.control.token:dev-recon-control}") String controlToken) {
        this.settlementService = settlementService;
        this.controlToken = controlToken;
    }

    @PostMapping("/{id}/settlement/force")
    public ResponseEntity<Void> forceSettle(@PathVariable("id") String id,
                                            @RequestHeader("X-Recon-Control-Token") String token,
                                            @RequestHeader("X-Recon-Operator") String operator) {
        authorize(token, operator);
        SettlementService.ForceResult result = settlementService.forceSettle(id);
        log.info("settlement_control operator={} type=force id={} result={}", operator, id, result);
        return switch (result) {
            case SETTLED, ALREADY_SETTLED -> ResponseEntity.ok().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }

    private void authorize(String token, String operator) {
        if (!controlToken.equals(token) || operator == null || operator.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid recon-control credentials");
        }
    }
}
