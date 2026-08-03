package finos.traderx.tradeprocessor.controller;

import finos.traderx.tradeprocessor.auth.JwtTokenMinter;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * YU05 (post-trade-compliance, ADR-025): local development / testing token minting only — there
 * is no live OIDC provider in this environment. A token minted here validates against BOTH
 * services (they share {@code auth.jwt.secret}). Gated by its own master secret, distinct from
 * every per-capability control token, since minting is a materially more sensitive operation than
 * anything those tokens gate.
 */
@RestController
@RequestMapping("/auth")
public final class AuthController {
    private final JwtTokenMinter minter;
    private final String masterSecret;

    public AuthController(@Value("${auth.jwt.secret:dev-jwt-shared-secret}") String jwtSecret,
                          @Value("${auth.dev-token.master-secret:dev-token-master-secret}") String masterSecret) {
        this.minter = new JwtTokenMinter(jwtSecret);
        this.masterSecret = masterSecret;
    }

    public record DevTokenRequest(String subject, Set<Integer> accounts, boolean admin, long ttlSeconds) { }

    @PostMapping("/dev-token")
    public String devToken(@RequestHeader(value = "X-Auth-Master-Secret", required = false) String secret,
                           @RequestBody DevTokenRequest request) {
        if (!masterSecret.equals(secret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid master secret");
        }
        if (request.subject() == null || request.subject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject is required");
        }
        Set<Integer> accounts = request.accounts() == null ? Set.of() : request.accounts();
        long ttl = request.ttlSeconds() > 0 ? request.ttlSeconds() : 3600L;
        return minter.mint(request.subject(), accounts, request.admin(), ttl);
    }
}
