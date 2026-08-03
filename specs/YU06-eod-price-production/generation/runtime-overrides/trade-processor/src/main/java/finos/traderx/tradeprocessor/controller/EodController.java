package finos.traderx.tradeprocessor.controller;

import finos.traderx.tradeprocessor.auth.JwtAuthenticator;
import finos.traderx.tradeprocessor.auth.JwtPrincipal;
import finos.traderx.tradeprocessor.model.EodReport;
import finos.traderx.tradeprocessor.service.EodPriceService;
import finos.traderx.tradeprocessor.service.EodPriceService.PublishOutcome;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * YU06 (eod-price-production, contract-delta #1-4): the EOD price-production control surface, all
 * gated by an {@code admin} JWT (reuses YU05's {@link JwtAuthenticator}). A k8s CronJob calls
 * {@code /eod/session/close} on a demo schedule; operators use the same endpoints on demand plus
 * {@code /override} and {@code /publish} to resolve flagged instruments.
 */
@RestController
@RequestMapping("/eod")
public final class EodController {
    private static final Logger log = LoggerFactory.getLogger(EodController.class);

    private final EodPriceService eod;
    private final JwtAuthenticator jwt;

    public EodController(EodPriceService eod,
                         @Value("${auth.jwt.secret:dev-jwt-shared-secret}") String jwtSecret) {
        this.eod = eod;
        this.jwt = new JwtAuthenticator(jwtSecret);
    }

    public record OverrideRequest(String security, BigDecimal price, String reason) { }

    @PostMapping("/session/close")
    public ResponseEntity<EodReport> close(
            @RequestParam(name = "sessionDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sessionDate,
            @RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization);
        LocalDate date = sessionDate != null ? sessionDate : LocalDate.now();
        EodReport report = eod.produce(date);
        log.info("eod session close date={} version={} status={} flagged={}",
            date, report.version(), report.status(), report.flaggedCount());
        return ResponseEntity.ok(report);
    }

    @GetMapping("/prices/{sessionDate}")
    public ResponseEntity<EodReport> latest(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sessionDate,
            @RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization);
        return eod.latest(sessionDate).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/prices/{sessionDate}/versions/{version}")
    public ResponseEntity<EodReport> version(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sessionDate,
            @PathVariable int version,
            @RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization);
        return eod.find(sessionDate, version).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/prices/{sessionDate}/override")
    public ResponseEntity<EodReport> override(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sessionDate,
            @RequestBody OverrideRequest body,
            @RequestHeader("Authorization") String authorization) {
        JwtPrincipal principal = requireAdmin(authorization);
        if (body == null || body.security() == null || body.price() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "security and price are required");
        }
        Optional<EodReport> result = eod.override(sessionDate, body.security(), body.price(), body.reason());
        log.info("eod override subject={} date={} security={} present={}",
            principal.subject(), sessionDate, body.security(), result.isPresent());
        return result.map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/prices/{sessionDate}/publish")
    public ResponseEntity<EodReport> publish(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sessionDate,
            @RequestHeader("Authorization") String authorization) {
        JwtPrincipal principal = requireAdmin(authorization);
        PublishOutcome outcome = eod.publish(sessionDate);
        log.info("eod publish subject={} date={} status={}", principal.subject(), sessionDate, outcome.status());
        return switch (outcome.status()) {
            case PUBLISHED, ALREADY_PUBLISHED -> ResponseEntity.ok(outcome.report());
            case BLOCKED -> ResponseEntity.status(HttpStatus.CONFLICT).body(outcome.report());
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }

    private JwtPrincipal requireAdmin(String authorization) {
        JwtPrincipal principal = jwt.validate(authorization)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "valid JWT required"));
        if (!principal.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin claim required for EOD operations");
        }
        return principal;
    }
}
