package finos.traderx.tradeprocessor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finos.traderx.tradeprocessor.JetStreamFeed;
import finos.traderx.tradeprocessor.service.PartitionOwnership.Holder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-DRS-45: PROJECTION_FEED_PARTITIONS_OWNED's parse and the start-up guard's decision. */
class PartitionOwnershipTest {

  @Test void ownershipParsesListsRangesAndReplicaBlocks() {
    assertThat(JetStreamFeed.owned(null, 8)).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
    assertThat(JetStreamFeed.owned("all", 8)).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
    assertThat(JetStreamFeed.owned("", 1)).containsExactly(0);
    assertThat(JetStreamFeed.owned("0,1", 8)).containsExactly(0, 1);
    assertThat(JetStreamFeed.owned("0-1", 8)).containsExactly(0, 1);
    assertThat(JetStreamFeed.owned(" 6, 0-2 ", 8)).containsExactly(0, 1, 2, 6);
    assertThat(JetStreamFeed.owned("replica:0/2", 8)).containsExactly(0, 1, 2, 3);
    assertThat(JetStreamFeed.owned("replica:1/2", 8)).containsExactly(4, 5, 6, 7);
    assertThat(JetStreamFeed.owned("replica:2/3", 8)).containsExactly(6, 7);
    assertThat(JetStreamFeed.owned("replica:0/1", 8)).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
  }

  @Test void replicaBlocksCoverEveryPartitionExactlyOnce() {
    for (int n = 1; n <= 8; n++) {
      int[] seen = new int[8];
      for (int k = 0; k < n; k++) { for (int p : JetStreamFeed.owned("replica:" + k + "/" + n, 8)) { seen[p]++; } }
      assertThat(seen).as("N=" + n).containsOnly(1);
    }
  }

  @Test void aMalformedOrOverlappingOwnershipIsRefused() {
    for (String bad : new String[] {"8", "-1", "0,0", "0-3,2", "3-1", "0-1-2", "x", "replica:2/2", "replica:0/9",
        "replica:$(TP_REPLICA_INDEX)/2", "replica:1"}) {
      assertThatThrownBy(() -> JetStreamFeed.owned(bad, 8)).as(bad).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("PROJECTION_FEED_PARTITIONS_OWNED");
    }
  }

  @Test void onlyAnotherReplicasFreshHeartbeatRefuses() {
    long ttl = 20_000;
    List<Holder> rows = List.of(
        new Holder(0, "", "", Long.MAX_VALUE),              // never owned
        new Holder(1, "me", "0-3", 1_000),                  // my own row (a restarted pod of the same name)
        new Holder(2, "other", "2-3", 19_999),              // live elsewhere: refused
        new Holder(3, "gone", "2-3", 20_000));              // expired: a crashed predecessor, taken over
    assertThat(PartitionOwnership.liveOthers(rows, "me", ttl)).extracting(Holder::partition).containsExactly(2);
    assertThat(PartitionOwnership.liveOthers(rows.subList(0, 2), "me", ttl)).isEmpty();
  }
}
