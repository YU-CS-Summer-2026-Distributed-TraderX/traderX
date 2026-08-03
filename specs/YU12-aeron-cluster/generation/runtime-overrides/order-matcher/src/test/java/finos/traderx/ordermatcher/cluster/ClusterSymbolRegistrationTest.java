package finos.traderx.ordermatcher.cluster;

import finos.traderx.ordermatcher.lmax.AeronReplicationCodec;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Symbol identity as sequenced ingress (matrix finding F2): ids assigned in committed-log
 * order, idempotent by ticker, capacity-refused deterministically, and carried in the snapshot
 * so a recovered member resumes assignment strictly above every id ever issued.
 */
class ClusterSymbolRegistrationTest {
    private final AeronReplicationCodec codec = new AeronReplicationCodec();
    private final UnsafeBuffer symbolBuffer = new UnsafeBuffer(new byte[AeronReplicationCodec.SYMBOL_BYTES]);
    private long timestamp = 1_000_000_000_000L;

    @Test
    void assignsIdempotentIdsInLogOrder() {
        final MatchingEngineClusteredService service = newService();
        register(service, 1L, "IBM");
        register(service, 2L, "JPM");
        register(service, 3L, "IBM"); // duplicate: same id, no new assignment
        assertEquals(0, service.symbolIdFor("IBM"));
        assertEquals(1, service.symbolIdFor("JPM"));
        assertEquals(2, service.symbolCount());
    }

    @Test
    void mappingAndGeneratorSurviveSnapshotRestore() {
        final MatchingEngineClusteredService source = newService();
        register(source, 1L, "IBM");
        register(source, 2L, "JPM");

        final List<byte[]> records = new ArrayList<>();
        source.writeSnapshot((buffer, offset, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(offset, copy);
            records.add(copy);
        });
        final MatchingEngineClusteredService restored = newService();
        boolean done = false;
        for (final byte[] record : records) {
            done = restored.onSnapshotRecord(new UnsafeBuffer(record), 0);
        }
        assertTrue(done);
        assertEquals(0, restored.symbolIdFor("IBM"));
        assertEquals(1, restored.symbolIdFor("JPM"));

        // The id generator derives from the restored mapping: the next first-sighting takes 2.
        register(restored, 3L, "COF");
        assertEquals(2, restored.symbolIdFor("COF"));
    }

    @Test
    void refusesCapacityDeterministically() {
        final MatchingEngineClusteredService service = newService();
        for (int i = 0; i < MatchingEngineClusteredService.MAX_SECURITIES; i++) {
            register(service, i + 1L, "T" + i);
        }
        assertEquals(MatchingEngineClusteredService.MAX_SECURITIES, service.symbolCount());
        register(service, 999L, "OVERFLOW");
        assertEquals(-1, service.symbolIdFor("OVERFLOW"));
        assertEquals(MatchingEngineClusteredService.MAX_SECURITIES, service.symbolCount());
    }

    private MatchingEngineClusteredService newService() {
        final MatchingEngineClusteredService service = new MatchingEngineClusteredService();
        service.initEngine();
        return service;
    }

    private void register(final MatchingEngineClusteredService service, final long requestId,
                          final String ticker) {
        codec.encodeSymbolRegister(symbolBuffer, 0, requestId, ticker);
        service.onSessionMessage(null, ++timestamp, symbolBuffer, 0,
            AeronReplicationCodec.SYMBOL_BYTES, null);
    }
}
