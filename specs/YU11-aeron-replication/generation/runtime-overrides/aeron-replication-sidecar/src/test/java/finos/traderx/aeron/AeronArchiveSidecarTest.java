package finos.traderx.aeron;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AeronArchiveSidecarTest {
    @Test
    void schemaIdentityIsFailClosed() {
        AeronArchiveSidecar.Config matching = new AeronArchiveSidecar.Config(
            Path.of("aeron"), Path.of("archive"), 18080, "aeron:udp", "aeron:udp", "aeron:ipc",
            "aeron:udp", "aeron:udp", 1101,
            AeronArchiveSidecar.SCHEMA_CHECKSUM);
        AeronArchiveSidecar.Config foreign = new AeronArchiveSidecar.Config(
            Path.of("aeron"), Path.of("archive"), 18080, "aeron:udp", "aeron:udp", "aeron:ipc",
            "aeron:udp", "aeron:udp", 1101,
            "foreign");
        assertTrue(matching.schemaMatches());
        assertFalse(foreign.schemaMatches());
    }

    @Test
    void autoOutboundChannelUsesTheOtherStatefulSetOrdinal() {
        assertTrue(AeronArchiveSidecar.Config.resolveOutboundChannel(
            "auto", "order-matcher-0", "traderx")
            .contains("order-matcher-1.order-matcher-headless.traderx.svc.cluster.local:40123"));
    }
}
