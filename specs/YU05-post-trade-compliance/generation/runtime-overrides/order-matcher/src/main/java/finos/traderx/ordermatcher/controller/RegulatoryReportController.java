package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.auth.JwtAuthenticator;
import finos.traderx.ordermatcher.auth.JwtPrincipal;
import finos.traderx.ordermatcher.lmax.LmaxEngine;
import finos.traderx.ordermatcher.reporting.AuditRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * YU05 (post-trade-compliance, ADR-023/025, FR-PTC20-22): journal-sourced, CAT/TRACE-style audit
 * export for an input-sequence range. Spans all accounts by nature (an audit trail is never
 * scoped to one account), so requires an {@code admin} JWT claim (ADR-025) rather than a
 * per-account entitlement check.
 */
@RestController
@RequestMapping("/regulatory")
public final class RegulatoryReportController {
    private final LmaxEngine engine;
    private final JwtAuthenticator jwt;

    public RegulatoryReportController(LmaxEngine engine,
                                      @Value("${auth.jwt.secret:dev-jwt-shared-secret}") String jwtSecret) {
        this.engine = engine;
        this.jwt = new JwtAuthenticator(jwtSecret);
    }

    /** {@code toSeq <= 0} means "replay to the end of the journal". Synchronous and expensive. */
    @GetMapping("/report")
    public List<AuditRecord> report(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(name = "fromSeq", defaultValue = "0") long fromSeq,
            @RequestParam(name = "toSeq", defaultValue = "0") long toSeq) {
        requireAdmin(authorization);
        try {
            return engine.generateRegulatoryReport(fromSeq, toSeq);
        } catch (java.io.IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "regulatory report generation failed: " + ex.getMessage(), ex);
        }
    }

    private void requireAdmin(String authorization) {
        Optional<JwtPrincipal> principal = jwt.validate(authorization);
        if (principal.isEmpty() || !principal.get().admin()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin JWT required");
        }
    }
}
