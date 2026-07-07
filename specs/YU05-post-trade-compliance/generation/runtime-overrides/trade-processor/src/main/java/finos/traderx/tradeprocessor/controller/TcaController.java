package finos.traderx.tradeprocessor.controller;

import finos.traderx.tradeprocessor.auth.JwtAuthenticator;
import finos.traderx.tradeprocessor.auth.JwtPrincipal;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import finos.traderx.tradeprocessor.service.TcaService;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * YU05 (post-trade-compliance, ADR-024/025, FR-PTC30-32): on-demand execution-quality report for
 * a single trade, gated by a real JWT + per-account entitlement check — the caller must be
 * entitled to the account that owns the trade, or hold the {@code admin} claim.
 */
@RestController
@RequestMapping("/tca")
public final class TcaController {
    private final TcaService tcaService;
    private final TradeRepository tradeRepository;
    private final JwtAuthenticator jwt;

    public TcaController(TcaService tcaService, TradeRepository tradeRepository,
                         @Value("${auth.jwt.secret:dev-jwt-shared-secret}") String jwtSecret) {
        this.tcaService = tcaService;
        this.tradeRepository = tradeRepository;
        this.jwt = new JwtAuthenticator(jwtSecret);
    }

    @GetMapping("/report/{tradeId}")
    public TcaService.TcaReport report(@PathVariable("tradeId") String tradeId,
                                       @RequestHeader("Authorization") String authorization) {
        JwtPrincipal principal = jwt.validate(authorization)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "valid JWT required"));
        Optional<Trade> trade = tradeRepository.findById(tradeId);
        if (trade.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown trade: " + tradeId);
        }
        if (!principal.isEntitledTo(trade.get().getAccountId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "not entitled to account " + trade.get().getAccountId());
        }
        try {
            return tcaService.computeForTrade(tradeId);
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }
}
