package finos.traderx.tradeprocessor.controller;

import finos.traderx.tradeprocessor.auth.JwtAuthenticator;
import finos.traderx.tradeprocessor.auth.JwtPrincipal;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import finos.traderx.tradeprocessor.service.SettlementService;
import java.util.Optional;
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
 * YU05 (post-trade-compliance, ADR-025, contract-delta #4): manual settlement override, gated by
 * a real JWT + per-account entitlement check (FR-PTC40/41) — the caller must be entitled to the
 * account that owns the trade, or hold the {@code admin} claim.
 */
@RestController
@RequestMapping("/trades")
public final class SettlementController {
    private static final Logger log = LoggerFactory.getLogger(SettlementController.class);

    private final SettlementService settlementService;
    private final TradeRepository tradeRepository;
    private final JwtAuthenticator jwt;

    public SettlementController(SettlementService settlementService, TradeRepository tradeRepository,
                                @Value("${auth.jwt.secret:dev-jwt-shared-secret}") String jwtSecret) {
        this.settlementService = settlementService;
        this.tradeRepository = tradeRepository;
        this.jwt = new JwtAuthenticator(jwtSecret);
    }

    @PostMapping("/{id}/settlement/force")
    public ResponseEntity<Void> forceSettle(@PathVariable("id") String id,
                                            @RequestHeader("Authorization") String authorization) {
        JwtPrincipal principal = requirePrincipal(authorization);
        Optional<Trade> trade = tradeRepository.findById(id);
        if (trade.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!principal.isEntitledTo(trade.get().getAccountId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "not entitled to account " + trade.get().getAccountId());
        }
        SettlementService.ForceResult result = settlementService.forceSettle(id);
        log.info("settlement_control subject={} type=force id={} result={}", principal.subject(), id, result);
        return switch (result) {
            case SETTLED, ALREADY_SETTLED -> ResponseEntity.ok().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }

    private JwtPrincipal requirePrincipal(String authorization) {
        return jwt.validate(authorization)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "valid JWT required"));
    }
}
