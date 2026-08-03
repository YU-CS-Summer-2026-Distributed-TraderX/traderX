package finos.traderx.tradeprocessor.controller;

import finos.traderx.tradeprocessor.auth.JwtAuthenticator;
import finos.traderx.tradeprocessor.auth.JwtPrincipal;
import finos.traderx.tradeprocessor.service.ReconciliationService;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * YU05 (post-trade-compliance, ADR-025, contract-delta #3): last-sweep reconciliation summary.
 * {@code /status} is not account-scoped and not per-trade, so it stays open — it only ever
 * returns bounded aggregate counters. The full-history orphan sweep (FR-PTC10) DOES return actual
 * trade ids spanning every account, so it requires an {@code admin} JWT claim (ADR-025).
 */
@RestController
@RequestMapping("/recon")
public final class ReconStatusController {
    private final ReconciliationService reconciliationService;
    private final JwtAuthenticator jwt;

    public ReconStatusController(ReconciliationService reconciliationService,
                                 @Value("${auth.jwt.secret:dev-jwt-shared-secret}") String jwtSecret) {
        this.reconciliationService = reconciliationService;
        this.jwt = new JwtAuthenticator(jwtSecret);
    }

    @GetMapping("/status")
    public ReconciliationService.StatusSnapshot status() {
        return reconciliationService.status();
    }

    /** FR-PTC10: triggers order-matcher's full-history reindex, then diffs against local trades. */
    @PostMapping("/orphan-sweep")
    public ReconciliationService.OrphanSweepResult runOrphanSweep(
            @RequestHeader(value = "Authorization", required = false) String authorization) throws IOException, InterruptedException {
        requireAdmin(authorization);
        return reconciliationService.runOrphanSweep();
    }

    @GetMapping("/orphan-sweep/last")
    public ReconciliationService.OrphanSweepResult lastOrphanSweep(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        ReconciliationService.OrphanSweepResult last = reconciliationService.lastOrphanSweep();
        if (last == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no orphan sweep has run yet");
        }
        return last;
    }

    private void requireAdmin(String authorization) {
        Optional<JwtPrincipal> principal = jwt.validate(authorization);
        if (principal.isEmpty() || !principal.get().admin()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin JWT required");
        }
    }
}
