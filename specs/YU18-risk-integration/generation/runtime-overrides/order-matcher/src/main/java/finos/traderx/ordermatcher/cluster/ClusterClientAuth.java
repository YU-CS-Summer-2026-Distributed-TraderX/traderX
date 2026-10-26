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

    /** Client side: the env secret as connect credentials; none when unset (an open dev rig). */
    static CredentialsSupplier credentialsFromEnv() {
        return credentials(System.getenv(ENV));
    }

    static CredentialsSupplier credentials(final String secret) {
        final byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
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

    static final class SharedSecret implements Authenticator {
        private final byte[] want;
        private final AtomicLong rejected;
        private final Map<Long, Boolean> decided = new HashMap<>();   // consensus-module thread only

        SharedSecret(final byte[] want, final AtomicLong rejected) {
            this.want = want;
            this.rejected = rejected;
        }

        public void onConnectRequest(final long sessionId, final byte[] credentials, final long nowMs) {
            decided.put(sessionId, credentials != null && MessageDigest.isEqual(want, credentials));
        }

        public void onChallengeResponse(final long sessionId, final byte[] credentials, final long nowMs) {
            decided.put(sessionId, false);
        }

        /** Called every duty cycle until the session is resolved, so a back-pressured authenticate retries. */
        public void onConnectedSession(final SessionProxy proxy, final long nowMs) {
            if (Boolean.TRUE.equals(decided.get(proxy.sessionId()))) {
                if (proxy.authenticate(null)) {
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
