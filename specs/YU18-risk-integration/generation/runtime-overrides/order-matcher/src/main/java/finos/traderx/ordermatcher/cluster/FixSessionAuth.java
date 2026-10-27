package finos.traderx.ordermatcher.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * ENGINE-REVIEW S3: FIX session credentials and the accounts a session may act on.
 *
 * <p>Configured by {@code FIX_SESSION_AUTH="COMPID:password:acct1|acct2,COMPID2:password2:13"}.
 * Logon ({@code 35=A}) must then carry {@code Password(554)} matching the CompID's entry, and
 * every order-entry, cancel, replace and status request must name (or default to) one of that
 * CompID's accounts. An entry with no account list binds the session to the gateway's default
 * account only; {@code *} binds it to every account (an operator session, e.g. the console's FIX
 * ticket, which is itself behind operator sign-in; the cluster's risk gate still checks each account).
 *
 * <p>Unconfigured (empty env) is the pre-S3 behaviour — CompID membership alone, any
 * {@code Account(1)} honoured. The gateway refuses to start an acceptor that way unless
 * {@code ALLOW_DEV_SECRETS=true} (the kind rig's explicit opt-in, ENGINE-REVIEW S5), and the acceptor
 * then logs INSECURE at startup. Pure, so it is unit-tested without a FIX engine.
 */
final class FixSessionAuth {
    private record Entry(byte[] password, Set<Integer> accounts) { }   // accounts null = every account

    private final Map<String, Entry> byCompId = new HashMap<>();

    static FixSessionAuth fromEnv(final String spec) {
        final FixSessionAuth auth = new FixSessionAuth();
        if (spec == null || spec.isBlank()) {
            return auth;
        }
        for (final String entry : spec.split(",")) {
            final String[] parts = entry.trim().split(":", 3);
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("FIX_SESSION_AUTH entry must be COMPID:password[:acct|acct]");
            }
            Set<Integer> accounts = new HashSet<>();
            if (parts.length == 3 && "*".equals(parts[2].trim())) {
                accounts = null;
            } else if (parts.length == 3 && !parts[2].isBlank()) {
                for (final String a : parts[2].split("\\|")) {
                    accounts.add(Integer.parseInt(a.trim()));
                }
            }
            auth.byCompId.put(parts[0].trim(), new Entry(parts[1].getBytes(StandardCharsets.UTF_8), accounts));
        }
        return auth;
    }

    boolean configured() {
        return !byCompId.isEmpty();
    }

    /** Logon: configured ⇒ the CompID must have an entry and the password must match (constant time). */
    boolean logonAllowed(final String compId, final String password) {
        if (!configured()) {
            return true;
        }
        final Entry e = byCompId.get(compId);
        return e != null && password != null
            && MessageDigest.isEqual(e.password, password.getBytes(StandardCharsets.UTF_8));
    }

    /** Account binding: configured ⇒ the account must be in the CompID's list, be the default
     *  account when the list is empty, or be any account when the list is {@code *}. */
    boolean accountAllowed(final String compId, final int accountId, final int defaultAccountId) {
        if (!configured()) {
            return true;
        }
        final Entry e = byCompId.get(compId);
        if (e == null) {
            return false;
        }
        if (e.accounts == null) {
            return true;
        }
        return e.accounts.isEmpty() ? accountId == defaultAccountId : e.accounts.contains(accountId);
    }
}
