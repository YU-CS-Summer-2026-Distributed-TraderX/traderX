package finos.traderx.tradeprocessor.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-DRS-51 (450K acceptance run 4, 2026-11-11): the ledger recon reads the capture stream only as far as the blotter
 * page it classifies. It read until EVERY partition's head passed the page, and a partition with no trades (partition
 * 1: no load account maps to it) never does, so each sweep drained every stream to its head into {@code pending}.
 * That is every trade side, held on trade-processor-0 (the owner of partition 0, the only replica running the recon)
 * until it ran out of heap.
 */
@Tag("integration")
@Testcontainers
class ReconLedgerReaderIT {
  @Container static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.10-alpine").withCommand("-js")
      .withExposedPorts(4222);
  private static final int PARTITIONS = 3, SIDES = 20_000;

  @Test void anIdlePartitionDoesNotMakeTheReaderDrainEveryStream() throws Exception {
    try (Connection nc = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222))) {
      String[] streams = new String[PARTITIONS];
      String[] subjects = new String[PARTITIONS];
      for (int p = 0; p < PARTITIONS; p++) {
        streams[p] = "TRADERX_FEED_P" + p;
        subjects[p] = "/trades." + p;
        nc.jetStreamManagement().addStream(StreamConfiguration.builder().name(streams[p])
            .subjects(subjects[p], "/orders." + p).storageType(StorageType.Memory).build());
      }
      // Partitions 0 and 2 carry the trades, interleaved by tradeSeq; partition 1 carries none.
      for (int seq = 1; seq <= SIDES; seq++) {
        int p = seq % 2 == 0 ? 0 : 2;
        nc.jetStream().publish(subjects[p], side(seq, p).getBytes(StandardCharsets.UTF_8));
      }

      var reader = new ReconciliationService.LedgerReader(nc, streams, PARTITIONS, 0);
      reader.readThrough(100, 0, subjects);

      assertThat(reader.pending).as("the sides through the page's target").containsKeys("1-1-B", "1-100-B");
      assertThat(reader.headOf(subjects[0])).isGreaterThanOrEqualTo(100);
      assertThat(reader.headOf(subjects[2])).isGreaterThanOrEqualTo(100);
      // At most one fetch (1,000 messages) per stream past the target; the unfixed reader held all 20,000.
      assertThat(reader.pending.size()).as("sides held for a 100-side page").isLessThanOrEqualTo(100 + 2 * 1000);

      // The next page picks up where the last read stopped, still bounded.
      reader.prune(100);
      reader.readThrough(5_000, 100, subjects);
      assertThat(reader.pending).containsKey("1-5000-B");
      assertThat(reader.pending.size()).isLessThanOrEqualTo(4_900 + 2 * 1000);
    }
  }

  /** One side as the bridge publishes it: an envelope whose payload id is {@code <epoch>-<tradeSeq>-<side>}. */
  private static String side(int seq, int partition) {
    return "{\"topic\":\"/trades." + partition + "\",\"type\":\"TradeOrder\",\"payload\":{\"id\":\"1-" + seq
        + "-B\",\"accountId\":" + (9900000 + partition) + ",\"security\":\"AAPL\",\"side\":\"Buy\",\"quantity\":100,"
        + "\"price\":241.30}}";
  }
}
