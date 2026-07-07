package finos.traderx.tradeprocessor.controller;

import finos.traderx.tradeprocessor.service.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * YU05 (post-trade-compliance, contract-delta #3): last-sweep reconciliation summary. Not
 * account-scoped and not per-trade, so it does not need the token-gate the blotter/settlement
 * endpoints use — it only ever returns bounded aggregate counters.
 */
@RestController
@RequestMapping("/recon")
public final class ReconStatusController {
    private final ReconciliationService reconciliationService;

    public ReconStatusController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/status")
    public ReconciliationService.StatusSnapshot status() {
        return reconciliationService.status();
    }
}
