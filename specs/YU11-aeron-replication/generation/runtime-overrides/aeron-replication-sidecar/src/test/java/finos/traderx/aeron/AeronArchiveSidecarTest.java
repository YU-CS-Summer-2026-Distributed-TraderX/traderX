package finos.traderx.aeron;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config resolution for the replication sidecar.
 *
 * <p>{@code resolveOutboundChannel} decides which peer this pod records to, from nothing but its own
 * StatefulSet pod name. Getting it wrong is not a crash — it is a sidecar that quietly records to
 * the wrong endpoint, or to itself, which looks healthy from every angle until a failover needs the
 * data that was never replicated. That is why the uninterpretable cases assert a THROW: refusing to
 * start is the correct answer to an ordinal this rule cannot read.
 *
 * <p>Not covered here, deliberately: {@code Config.fromEnvironment()} and the malformed-integer
 * fallback in {@code integerEnv}. Both read the real process environment, which a JUnit test cannot
 * set portably, and reaching them by reflection would pin the private shape of the class rather
 * than its behaviour.
 */
class AeronArchiveSidecarTest {

    private static AeronArchiveSidecar.Config config(String expectedChecksum) {
        return new AeronArchiveSidecar.Config(
            Path.of("aeron"), Path.of("archive"), 18080, "aeron:udp", "aeron:udp", "aeron:ipc",
            "aeron:udp", "aeron:udp", 1101, expectedChecksum);
    }

    @Test
    void schemaIdentityIsFailClosed() {
        assertTrue(config(AeronArchiveSidecar.SCHEMA_CHECKSUM).schemaMatches());
        assertFalse(config("foreign").schemaMatches());
    }

    @Test
    void autoOutboundChannelUsesTheOtherStatefulSetOrdinal() {
        assertTrue(AeronArchiveSidecar.Config.resolveOutboundChannel(
            "auto", "order-matcher-0", "traderx")
            .contains("order-matcher-1.order-matcher-headless.traderx.svc.cluster.local:40123"));
    }

    /** The mirror of the case above — member 1 must point back at member 0, not at itself. */
    @Test
    void autoOutboundChannelIsSymmetricForTheOtherMember() {
        String resolved = AeronArchiveSidecar.Config.resolveOutboundChannel(
            "auto", "order-matcher-1", "traderx");

        assertTrue(resolved.contains("order-matcher-0.order-matcher-headless.traderx.svc.cluster.local:40123"),
            "member 1 must record to member 0, resolved: " + resolved);
        assertFalse(resolved.contains("order-matcher-1.order-matcher-headless"),
            "a pod recording to ITSELF replicates nothing while looking healthy: " + resolved);
    }

    @Test
    void autoOutboundChannelHonoursANonDefaultNamespace() {
        assertTrue(AeronArchiveSidecar.Config.resolveOutboundChannel(
            "auto", "order-matcher-0", "traderx-staging")
            .contains(".traderx-staging.svc.cluster.local:"));
    }

    /** "auto" is matched case-insensitively, so AUTO from a manifest resolves rather than being taken verbatim. */
    @Test
    void autoIsRecognisedRegardlessOfCase() {
        assertTrue(AeronArchiveSidecar.Config.resolveOutboundChannel(
            "AUTO", "order-matcher-0", "traderx").startsWith("aeron:udp?endpoint=order-matcher-1."));
    }

    /** An explicit channel is passed through untouched — the pod name is irrelevant to it. */
    @Test
    void anExplicitChannelIsPassedThroughUnchanged() {
        String explicit = "aeron:udp?endpoint=some-host:40123|alias=custom";

        assertEquals(explicit, AeronArchiveSidecar.Config.resolveOutboundChannel(
            explicit, "not-a-statefulset-name", "traderx"));
    }

    /** Blank or null means outbound recording is switched OFF, and must not resolve to a channel. */
    @Test
    void aBlankOrNullChannelDisablesOutboundRecording() {
        assertEquals("", AeronArchiveSidecar.Config.resolveOutboundChannel("", "order-matcher-0", "traderx"));
        assertEquals("", AeronArchiveSidecar.Config.resolveOutboundChannel("   ", "order-matcher-0", "traderx"));
        assertEquals("", AeronArchiveSidecar.Config.resolveOutboundChannel(null, "order-matcher-0", "traderx"));
    }

    /**
     * `auto` only means something for a two-member StatefulSet. A third ordinal, a missing ordinal
     * or an empty pod name has no defensible peer, so the sidecar refuses to start rather than
     * guessing an endpoint and replicating into the void.
     */
    @Test
    void autoRefusesAPodNameItCannotInterpret() {
        for (String podName : new String[] {"order-matcher-2", "order-matcher", ""}) {
            assertThrows(IllegalArgumentException.class,
                () -> AeronArchiveSidecar.Config.resolveOutboundChannel("auto", podName, "traderx"),
                "expected a refusal for pod name: '" + podName + "'");
        }
        assertThrows(IllegalArgumentException.class,
            () -> AeronArchiveSidecar.Config.resolveOutboundChannel("auto", null, "traderx"),
            "expected a refusal for a null pod name");
    }
}
