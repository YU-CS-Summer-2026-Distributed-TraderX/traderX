package finos.traderx.ordermatcher.cluster;

import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.security.CredentialsSupplier;
import org.agrona.CloseHelper;
import org.agrona.ExpandableArrayBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-079 §4 on a real 3-member cluster: with CLUSTER_CLIENT_SECRET set, a session with the secret connects
 * and its commands are applied; a wrong secret and no credentials at all are refused; and refused attempts
 * do not consume the session budget — with maxConcurrentSessions=2, ten refused connects later, two good
 * clients still both get in (the lockout an open ingress allowed: any pod could fill the budget the
 * gateways need).
 */
@Timeout(180)
class ClusterIngressAuthTest {
    private static final int PORT_BASE = TestPorts.freeBase(3);
    private static final List<String> HOSTS = List.of("localhost", "localhost", "localhost");
    private static final String SECRET = "test-cluster-client-secret-7f3a";

    @TempDir
    Path tmp;

    private final ClusteredMediaDriver[] drivers = new ClusteredMediaDriver[3];
    private final ClusteredServiceContainer[] containers = new ClusteredServiceContainer[3];
    private final ArchivePurgeTest.CountingService[] services = new ArchivePurgeTest.CountingService[3];
    private final List<AeronCluster> clients = new ArrayList<>();
    private MediaDriver clientDriver;

    @AfterEach
    void tearDown() {
        clients.forEach(CloseHelper::quietClose);
        CloseHelper.quietClose(clientDriver);
        for (int i = 0; i < 3; i++) {
            CloseHelper.quietCloseAll(containers[i], drivers[i]);
        }
    }

    @Test
    void onlyTheSecretGetsInAndRefusalsDoNotSpendSessions() throws Exception {
        final AtomicLong rejected = new AtomicLong();
        for (int i = 0; i < 3; i++) {
            services[i] = new ArchivePurgeTest.CountingService();
            final ClusterNodeConfig.Contexts c = ClusterNodeConfig.contexts(i, HOSTS, PORT_BASE,
                new File(tmp.toFile(), "aeron-" + i).getAbsolutePath(), new File(tmp.toFile(), "node-" + i),
                services[i], true);
            c.consensusModule().maxConcurrentSessions(2)
                .authenticatorSupplier(ClusterClientAuth.authenticator(SECRET, rejected));
            drivers[i] = ClusteredMediaDriver.launch(c.mediaDriver(), c.archive(), c.consensusModule());
            containers[i] = ClusteredServiceContainer.launch(c.container());
        }
        clientDriver = MediaDriver.launchEmbedded(new MediaDriver.Context().threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnShutdown(true));

        // Negative controls first, ten of them, against a budget of two.
        for (int i = 0; i < 5; i++) {
            assertThrows(Exception.class, () -> connect(ClusterClientAuth.credentials("wrong-secret")),
                "a wrong secret is refused");
            assertThrows(Exception.class, () -> connect(ClusterClientAuth.credentials(null)),
                "no credentials is refused");
        }
        assertTrue(rejected.get() >= 10, "the leader counted the refusals: " + rejected.get());

        final AeronCluster a = connect(ClusterClientAuth.credentials(SECRET));
        final AeronCluster b = connect(ClusterClientAuth.credentials(SECRET));
        clients.add(a);
        clients.add(b);
        final ExpandableArrayBuffer msg = new ExpandableArrayBuffer(8);
        msg.putLong(0, 42);
        for (final AeronCluster c : List.of(a, b)) {
            while (c.offer(msg, 0, 8) < 0) {
                c.pollEgress();
                Thread.onSpinWait();
            }
        }
        final long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && !allApplied(2)) {
            a.pollEgress();
            b.pollEgress();
            Thread.sleep(5);
        }
        for (final ArchivePurgeTest.CountingService s : services) {
            assertEquals(2, s.applied.get(), "both authenticated sessions' commands were applied on every member");
        }
        assertThrows(Exception.class, () -> connect(ClusterClientAuth.credentials(SECRET)),
            "the budget is real: a third good client finds both slots taken");
    }

    private boolean allApplied(final long n) {
        for (final ArchivePurgeTest.CountingService s : services) {
            if (s.applied.get() != n) {
                return false;
            }
        }
        return true;
    }

    private AeronCluster connect(final CredentialsSupplier credentials) {
        final AeronCluster c = AeronCluster.connect(new AeronCluster.Context()
            .aeronDirectoryName(clientDriver.aeronDirectoryName())
            .ingressChannel("aeron:udp")
            .ingressEndpoints(ClusterNodeConfig.ingressEndpoints(HOSTS, PORT_BASE))
            .egressChannel("aeron:udp?endpoint=localhost:0")
            .messageTimeoutNs(5_000_000_000L)
            .credentialsSupplier(credentials));
        return c;
    }
}
