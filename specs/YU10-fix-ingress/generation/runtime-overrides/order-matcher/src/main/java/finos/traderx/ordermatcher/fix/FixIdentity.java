package finos.traderx.ordermatcher.fix;

import finos.traderx.ordermatcher.auth.EntitlementGate;
import finos.traderx.ordermatcher.auth.JwtAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.RejectLogon;

import java.util.HashMap;
import java.util.Map;

/**
 * Fail-closed FIX session identity (ADR-036, FR-FIX07/FR-FIX08).
 *
 * <p>Every logon must present (a) a SenderCompID in the committed FIX_SESSION_ACCOUNTS allowlist
 * and (b) a JWT in Password(554) whose principal is entitled to the mapped account. Resolution
 * goes through the SAME {@link EntitlementGate#resolve} the REST path uses — but with enforcement
 * hard-wired ON: the REST flag (`risk.entitlement.enforced`, default false) governs the REST
 * rollout only; a brand-new ingress surface has no installed base to keep compatible. There is
 * no fallback account for unmapped CompIDs.
 */
public final class FixIdentity {
    private static final Logger log = LoggerFactory.getLogger(FixIdentity.class);

    /** The identity a successful logon pins to the session for its whole lifetime. */
    public record Authenticated(int accountId, String bearer) { }

    private final JwtAuthenticator jwt;
    private final Map<String, Integer> compIdToAccount;

    public FixIdentity(JwtAuthenticator jwt, String sessionAccountsSpec) {
        this.jwt = jwt;
        this.compIdToAccount = parse(sessionAccountsSpec);
    }

    /** {@code COMPID:accountId[,COMPID:accountId...]} — the complete logon allowlist. */
    static Map<String, Integer> parse(String spec) {
        Map<String, Integer> map = new HashMap<>();
        if (spec == null || spec.isBlank()) {
            return map;
        }
        for (String pair : spec.split(",")) {
            String[] kv = pair.trim().split(":");
            if (kv.length != 2 || kv[0].isBlank()) {
                throw new IllegalArgumentException("FIX_SESSION_ACCOUNTS entry not COMPID:accountId: '" + pair + "'");
            }
            map.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
        }
        return map;
    }

    public java.util.Set<String> allowedCompIds() {
        return compIdToAccount.keySet();
    }

    /** @throws RejectLogon on ANY miss — unmapped CompID, absent/invalid JWT, unentitled principal. */
    public Authenticated authenticate(String compId, String password) throws RejectLogon {
        Integer accountId = compIdToAccount.get(compId);
        if (accountId == null) {
            log.warn("FIX logon rejected: unmapped CompID {}", compId);
            throw new RejectLogon("unknown CompID");
        }
        if (password == null || password.isBlank()) {
            throw new RejectLogon("credentials required");
        }
        String bearer = password.startsWith("Bearer ") ? password : "Bearer " + password;
        try {
            EntitlementGate.ResolvedPrincipal principal = EntitlementGate.resolve(jwt, true, bearer);
            principal.checkAccount(accountId);
        } catch (Exception ex) {
            log.warn("FIX logon rejected for CompID {}: {}", compId, ex.toString());
            throw new RejectLogon("authentication failed");
        }
        return new Authenticated(accountId, bearer);
    }
}
