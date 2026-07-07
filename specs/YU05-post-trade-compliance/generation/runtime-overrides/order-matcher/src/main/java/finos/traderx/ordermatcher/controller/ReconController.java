package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.lmax.TradeBlotter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * YU05 (post-trade-compliance, ADR-022/contract-delta #2): read-only, forward-paginated access
 * to the replay-safe trade blotter, for trade-processor's reconciliation sweep. Same
 * authenticated-control-plane pattern as {@code /risk/control/*} (token + operator header) until
 * real OIDC/entitlements (ADR-025) lands — this endpoint exposes account-level trade data, so it
 * is never left open.
 */
@RestController
@RequestMapping("/recon")
public final class ReconController {
    private static final int DEFAULT_PAGE_SIZE = 1000;

    private final TradeBlotter blotter;
    private final String controlToken;
    private final int pageSize;

    public ReconController(TradeBlotter blotter,
                           @Value("${recon.control.token:dev-recon-control}") String controlToken,
                           @Value("${recon.blotter.page-size:1000}") int pageSize) {
        this.blotter = blotter;
        this.controlToken = controlToken;
        this.pageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
    }

    @GetMapping("/trades/blotter")
    public List<TradeBlotter.TradeRecord> blotter(
            @RequestHeader("X-Recon-Control-Token") String token,
            @RequestHeader("X-Recon-Operator") String operator,
            @RequestParam(name = "sinceSeq", defaultValue = "0") long sinceSeq) {
        authorize(token, operator);
        return blotter.since(sinceSeq, pageSize);
    }

    private void authorize(String token, String operator) {
        if (!controlToken.equals(token) || operator == null || operator.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid recon-control credentials");
        }
    }
}
