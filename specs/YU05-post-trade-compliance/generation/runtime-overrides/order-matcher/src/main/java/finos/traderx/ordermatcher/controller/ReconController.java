package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.auth.JwtAuthenticator;
import finos.traderx.ordermatcher.auth.JwtPrincipal;
import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.lmax.TradeBlotter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * YU05 (post-trade-compliance, ADR-022/025, contract-delta #2): read-only, forward-paginated
 * access to the replay-safe trade blotter, for trade-processor's reconciliation sweep. Every
 * endpoint here spans all accounts (the blotter/full-history index aren't scoped to one account),
 * so all of them require an {@code admin} JWT claim (ADR-025) — a per-account entitlement check
 * doesn't apply.
 *
 * <p>Also exposes the on-demand full-history reindex (FR-PTC10, {@link
 * LmaxEngine#reindexFullHistory()}) for orphan-in-projection detection — an expensive,
 * explicitly-triggered operation, never scheduled and never part of the normal live blotter path.
 */
@RestController
@RequestMapping("/recon")
public final class ReconController {
    private static final int DEFAULT_PAGE_SIZE = 1000;

    private final TradeBlotter blotter;
    private final LmaxEngine engine;
    private final JwtAuthenticator jwt;
    private final int pageSize;

    public ReconController(TradeBlotter blotter, LmaxEngine engine,
                           @Value("${auth.jwt.secret:dev-jwt-shared-secret}") String jwtSecret,
                           @Value("${recon.blotter.page-size:1000}") int pageSize) {
        this.blotter = blotter;
        this.engine = engine;
        this.jwt = new JwtAuthenticator(jwtSecret);
        this.pageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
    }

    @GetMapping("/trades/blotter")
    public List<TradeBlotter.TradeRecord> blotter(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(name = "sinceSeq", defaultValue = "0") long sinceSeq) {
        requireAdmin(authorization);
        return blotter.since(sinceSeq, pageSize);
    }

    /** FR-PTC10: triggers a full journal replay. Synchronous and expensive — the caller waits. */
    @PostMapping("/full-history/reindex")
    public Map<String, Object> reindexFullHistory(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        TradeBlotter index;
        try {
            index = engine.reindexFullHistory();
        } catch (java.io.IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "full-history reindex failed: " + ex.getMessage(), ex);
        }
        return Map.of("indexedTrades", index.size(), "evictions", index.evictionCount());
    }

    @GetMapping("/full-history/trades")
    public List<TradeBlotter.TradeRecord> fullHistoryTrades(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(name = "sinceSeq", defaultValue = "0") long sinceSeq) {
        requireAdmin(authorization);
        TradeBlotter index = engine.fullHistoryIndex();
        if (index == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "no full-history index yet; POST /recon/full-history/reindex first");
        }
        return index.since(sinceSeq, pageSize);
    }

    private void requireAdmin(String authorization) {
        Optional<JwtPrincipal> principal = jwt.validate(authorization);
        if (principal.isEmpty() || !principal.get().admin()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin JWT required");
        }
    }
}
