package finos.traderx.ordermatcher.cluster;

import io.aeron.security.Authenticator;
import io.aeron.security.AuthenticatorSupplier;
import io.aeron.security.CredentialsSupplier;
import io.aeron.security.SessionProxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cluster ingress authentication (ADR-079 §4, EOD-RISK item 5). ENGINE-REVIEW S1-S6 put credentials on
 * every HTTP and FIX route, but the Aeron ingress behind them stayed open: any process that could reach a
 * member's client-facing UDP port could connect a session and submit sequenced commands — orders, extract
 * markers, symbol registrations — that the gateway's checks never see, and could take sessions out of the
 * {@code CLUSTER_MAX_SESSIONS} budget the gateways need.
 *
 * <p>A shared secret ({@code CLUSTER_CLIENT_SECRET}) travels as the connect request's credentials; the
 * member admits a session only on a constant-time match and rejects it otherwise, before it holds a
 * session slot past the connect. One secret, not per-client identities: every legitimate client (gateway,
 * risk-extract, feed adapter, proof client) is trusted infrastructure in the same namespace, and the
 * question this answers is "is it ours", not "which of ours". // ponytail: per-client secrets when a
 * client class needs revoking alone.
 */
final class ClusterClientAuth {

    static final String ENV = "CLUSTER_CLIENT_SECRET";

    private ClusterClientAuth() {
    }

    /** FR-FFR-14: the name a gateway connects with, which the member makes the session's principal. */
    static final String GATEWAY_ID = "GATEWAY_ID";
    static final String NAME_PATTERN = "[A-Za-z0-9._-]{1,63}";

    /** Client side: the env secret as connect credentials, and the gateway's name after it when {@code GATEWAY_ID}
     *  is set; none when unset (an open dev rig). */
    static CredentialsSupplier credentialsFromEnv() {
        return credentials(System.getenv(ENV), System.getenv(GATEWAY_ID));
    }

    static CredentialsSupplier credentials(final String secret) {
        return credentials(secret, null);
    }

    /**
     * FR-FFR-14 (ADR-084 amendment): a gateway names itself to the cluster. The credentials are {@code secret '\n'
     * name}; the member admits on the secret as before and makes the name the session's encoded principal, which the
     * engine stamps on each NEW order's booking event ({@code gateway}) so the order's fills can be routed back to the
     * gateway that owns the session ({@code /fills.<name>}), instead of every gateway reading the whole order feed. A
     * client that sends the secret alone (risk-extract, the feed adapter, a proof client) has no principal and its
     * orders are not routed. The name is a subject token: {@value #NAME_PATTERN}.
     */
    static CredentialsSupplier credentials(final String secret, final String name) {
        final String id = name == null ? "" : name.trim();
        if (!id.isEmpty() && !id.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException(GATEWAY_ID + " is not a subject token: " + id);
        }
        final String all = (secret == null ? "" : secret) + (id.isEmpty() ? "" : "\n" + id);
        final byte[] bytes = all.getBytes(StandardCharsets.UTF_8);
        return new CredentialsSupplier() {
            public byte[] encodedCredentials() {
                return bytes;
            }

            public byte[] onChallenge(final byte[] challenge) {
                return new byte[0];   // the member never challenges
            }
        };
    }

    /** Member side: admits only sessions whose credentials equal {@code secret}. */
    static AuthenticatorSupplier authenticator(final String secret, final AtomicLong rejected) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(ENV + " is empty");
        }
        final byte[] want = secret.getBytes(StandardCharsets.UTF_8);
        return () -> new SharedSecret(want, rejected);
    }

    /** Member side on an open dev rig ({@code CLUSTER_CLIENT_SECRET} unset, ALLOW_DEV_SECRETS): every session is
     *  admitted, but a gateway's name still becomes its principal, so fills route the same way they do in production
     *  (FR-FFR-14 does not depend on the ingress being closed). */
    static AuthenticatorSupplier open(final AtomicLong rejected) {
        return () -> new SharedSecret(null, rejected);
    }

    /** The secret part of the credentials: everything before the first newline. */
    static byte[] secretOf(final byte[] credentials) {
        final int nl = newlineAt(credentials);
        return nl < 0 ? credentials : java.util.Arrays.copyOfRange(credentials, 0, nl);
    }

    /** The name after the first newline, or null when the client sent the secret alone. */
    static byte[] principalOf(final byte[] credentials) {
        final int nl = newlineAt(credentials);
        return nl < 0 || nl + 1 >= credentials.length ? null : java.util.Arrays.copyOfRange(credentials, nl + 1, credentials.length);
    }

    private static int newlineAt(final byte[] credentials) {
        if (credentials == null) {
            return -1;
        }
        for (int i = 0; i < credentials.length; i++) {
            if (credentials[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static final byte[] NO_PRINCIPAL = new byte[0];

    static final class SharedSecret implements Authenticator {
        private final byte[] want;
        private final AtomicLong rejected;
        /** {@code want} null: an open rig, every session admitted (see {@link #open}). */
        /** sessionId -> the principal to authenticate with (empty: none), or null for a rejected session.
         *  Consensus-module thread only. */
        private final Map<Long, byte[]> decided = new HashMap<>();

        SharedSecret(final byte[] want, final AtomicLong rejected) {
            this.want = want;
            this.rejected = rejected;
        }

        public void onConnectRequest(final long sessionId, final byte[] credentials, final long nowMs) {
            final boolean ok = want == null || (credentials != null && MessageDigest.isEqual(want, secretOf(credentials)));
            final byte[] principal = ok ? principalOf(credentials) : null;
            decided.put(sessionId, !ok ? null : principal == null ? NO_PRINCIPAL : principal);
        }

        public void onChallengeResponse(final long sessionId, final byte[] credentials, final long nowMs) {
            decided.put(sessionId, null);
        }

        /** Called every duty cycle until the session is resolved, so a back-pressured authenticate retries. */
        public void onConnectedSession(final SessionProxy proxy, final long nowMs) {
            final byte[] principal = decided.get(proxy.sessionId());
            if (principal != null) {
                if (proxy.authenticate(principal.length == 0 ? null : principal)) {
                    decided.remove(proxy.sessionId());
                }
                return;
            }
            decided.remove(proxy.sessionId());
            rejected.incrementAndGet();
            System.out.println("CLUSTER-INGRESS-REJECTED session=" + proxy.sessionId()
                + " reason=bad-or-missing-credentials");
            proxy.reject();
        }

        public void onChallengedSession(final SessionProxy proxy, final long nowMs) {
            decided.remove(proxy.sessionId());
            rejected.incrementAndGet();
            proxy.reject();
        }
    }
}
