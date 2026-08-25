package finos.traderx.tradeprocessor.controller;

import finos.traderx.tradeprocessor.auth.JwtAuthenticator;
import finos.traderx.tradeprocessor.auth.JwtPrincipal;
import finos.traderx.tradeprocessor.model.EodReport;
import finos.traderx.tradeprocessor.repository.EodPreviousSessionRepository;
import finos.traderx.tradeprocessor.repository.EodPreviousSessionRepository.SessionRef;
import finos.traderx.tradeprocessor.repository.EodPriceSnapshotRepository;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * YU17 (ADR-069 rules 2 and 3): the previous-session read, resolved server-side.
 *
 * <p><b>Rule 3, and it is the reason this endpoint exists at all.</b> {@code price-publisher} owns
 * no persistence and must not acquire a schema dependency to gain a bootstrap. It already fetches
 * over the network ({@code fred-curve.js}); this is the same shape. {@code GET
 * /eod/prices/{sessionDate}} was already here but needs a date the publisher does not know — so
 * the one genuinely new piece of surface is a <i>previous-session</i> read that resolves rule 2
 * where the version and DRAFT/PUBLISHED semantics already live, rather than shipping a second
 * implementation of them to a Node service.
 *
 * <p>Additive, in its own class rather than as a method on {@link EodController}: nothing about
 * the existing surface changes, and the state pack keeps one copy of the controller it already
 * had. Same {@code /eod} prefix, same admin-JWT gate as every sibling — a machine caller mints a
 * short-lived admin token from {@code POST /auth/dev-token}, exactly as the
 * {@code eod-session-close} CronJob already does.
 *
 * <p>404 is the honest answer when nothing qualifies (no published session strictly before the
 * date), and the publisher treats it as "fall through to the seed" rather than as an error. It
 * MUST stay distinguishable from an empty 200: an empty session and an absent one lead to the same
 * prices, and the publisher's {@code /health} has to be able to say which one happened.
 */
@RestController
@RequestMapping("/eod")
public final class EodPreviousSessionController {

    private final EodPreviousSessionRepository previous;
    private final EodPriceSnapshotRepository snapshots;
    private final JwtAuthenticator jwt;

    public EodPreviousSessionController(EodPreviousSessionRepository previous,
                                        EodPriceSnapshotRepository snapshots,
                                        @Value("${auth.jwt.secret:dev-jwt-shared-secret}") String jwtSecret) {
        this.previous = previous;
        this.snapshots = snapshots;
        this.jwt = new JwtAuthenticator(jwtSecret);
    }

    /**
     * The session an opening price should come from. {@code before} defaults to today in the
     * service's own clock (UTC in the container — the same convention {@code EodController.close}
     * uses, and the reason a host-local date here would diverge every evening).
     */
    @GetMapping("/session/previous")
    public ResponseEntity<EodReport> previousSession(
            @RequestParam(name = "before", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate before,
            @RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization);
        LocalDate openingDate = before != null ? before : LocalDate.now();
        return previous.resolvePrevious(openingDate)
            .flatMap(ref -> snapshots.find(ref.sessionDate(), ref.version()))
            .<ResponseEntity<EodReport>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Same gate as EodController's, duplicated rather than shared: EodController's copy is private
    // and lives in another state's spec pack, and seven lines here is a smaller cost than editing
    // that file to widen its visibility. Both read the one auth.jwt.secret, so a token works
    // against either.
    private JwtPrincipal requireAdmin(String authorization) {
        JwtPrincipal principal = jwt.validate(authorization)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "valid JWT required"));
        if (!principal.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin claim required for EOD operations");
        }
        return principal;
    }
}
