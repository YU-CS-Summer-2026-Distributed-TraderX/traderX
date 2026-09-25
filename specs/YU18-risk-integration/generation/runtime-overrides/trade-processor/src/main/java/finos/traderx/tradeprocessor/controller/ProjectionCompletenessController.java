package finos.traderx.tradeprocessor.controller;

import finos.traderx.tradeprocessor.service.AutomaticProjectionRecovery;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only automatic catch-up status. Exposes no operator action and no credentials. */
@RestController
public final class ProjectionCompletenessController {
    private final AutomaticProjectionRecovery recovery;
    public ProjectionCompletenessController(AutomaticProjectionRecovery recovery) { this.recovery=recovery; }
    @GetMapping("/v2/projection-completeness")
    public Map<String,Object> status() { return recovery.status(); }
}
