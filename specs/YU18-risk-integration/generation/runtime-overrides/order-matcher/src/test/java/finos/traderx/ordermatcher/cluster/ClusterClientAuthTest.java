package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeron.security.SessionProxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** FR-FFR-14: a gateway's name travels after the secret and becomes the session's principal; the secret alone admits
 *  with no principal; a wrong secret is rejected whatever the name. */
class ClusterClientAuthTest {
    static final class Proxy implements SessionProxy {
        final long id;
        byte[] principal;
        boolean authenticated;
        boolean rejected;
        Proxy(final long id) { this.id = id; }
        public long sessionId() { return id; }
        public boolean authenticate(final byte[] encodedPrincipal) { authenticated = true; principal = encodedPrincipal; return true; }
        public boolean challenge(final byte[] encodedChallenge) { return true; }
        public void reject() { rejected = true; }
    }

    private static Proxy connect(final ClusterClientAuth.SharedSecret auth, final long id, final byte[] credentials) {
        auth.onConnectRequest(id, credentials, 0L);
        final Proxy p = new Proxy(id);
        auth.onConnectedSession(p, 0L);
        return p;
    }

    @Test
    void aNamedGatewayIsAdmittedWithItsNameAsThePrincipal() {
        final ClusterClientAuth.SharedSecret auth = new ClusterClientAuth.SharedSecret("s3cret".getBytes(StandardCharsets.UTF_8), new AtomicLong());
        final Proxy p = connect(auth, 1, ClusterClientAuth.credentials("s3cret", "cluster-gateway-7c9").encodedCredentials());
        assertTrue(p.authenticated);
        assertFalse(p.rejected);
        assertArrayEquals("cluster-gateway-7c9".getBytes(StandardCharsets.UTF_8), p.principal);
    }

    @Test
    void theSecretAloneIsAdmittedWithNoPrincipal() {
        final ClusterClientAuth.SharedSecret auth = new ClusterClientAuth.SharedSecret("s3cret".getBytes(StandardCharsets.UTF_8), new AtomicLong());
        final Proxy p = connect(auth, 2, ClusterClientAuth.credentials("s3cret").encodedCredentials());
        assertTrue(p.authenticated);
        assertNull(p.principal);
        assertArrayEquals("s3cret".getBytes(StandardCharsets.UTF_8), ClusterClientAuth.credentials("s3cret", null).encodedCredentials());
    }

    @Test
    void aWrongSecretIsRejectedWhateverTheName() {
        final AtomicLong rejected = new AtomicLong();
        final ClusterClientAuth.SharedSecret auth = new ClusterClientAuth.SharedSecret("s3cret".getBytes(StandardCharsets.UTF_8), rejected);
        final Proxy p = connect(auth, 3, ClusterClientAuth.credentials("wrong", "cluster-gateway-7c9").encodedCredentials());
        assertTrue(p.rejected);
        assertFalse(p.authenticated);
        assertEquals(1, rejected.get());
        // the name is not a secret: "s3cret\ngateway" never matches a secret "s3cret\ngateway" by accident either
        final Proxy q = connect(auth, 4, "s3cret\ncluster-gateway-7c9".getBytes(StandardCharsets.UTF_8));
        assertTrue(q.authenticated);
        assertArrayEquals("cluster-gateway-7c9".getBytes(StandardCharsets.UTF_8), q.principal);
    }

    @Test
    void aNameThatIsNotASubjectTokenIsRefusedAtTheClient() {
        assertThrows(IllegalArgumentException.class, () -> ClusterClientAuth.credentials("s3cret", "gw/one"));
        assertThrows(IllegalArgumentException.class, () -> ClusterClientAuth.credentials("s3cret", "gw one"));
        assertThrows(IllegalArgumentException.class, () -> ClusterClientAuth.credentials("s3cret", "gw.>"));
    }
}
