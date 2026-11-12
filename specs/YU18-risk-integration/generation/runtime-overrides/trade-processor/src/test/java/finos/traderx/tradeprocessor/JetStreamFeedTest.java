package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JetStreamFeedTest {
  @Test void singlePartitionKeepsTheBareSubject() {
    assertEquals("/trades", JetStreamFeed.subject("/trades", 0, 1));
    assertEquals("/orders.3", JetStreamFeed.subject("/orders", 3, 4));
  }

  @Test void ownedPartitionsParseAndRefuseOutOfRange() {
    assertArrayEquals(new int[] {0, 1, 2}, JetStreamFeed.owned("all", 3));
    assertArrayEquals(new int[] {0}, JetStreamFeed.owned("", 1));
    assertArrayEquals(new int[] {1, 3}, JetStreamFeed.owned(" 1, 3", 4));
    assertThrows(IllegalArgumentException.class, () -> JetStreamFeed.owned("4", 4));
  }

  /** FR-DRS-43: a fetch is sized to about FETCH_BYTES from the last batch's average message size. */
  @org.junit.jupiter.api.Test
  void aFetchIsSizedByTheLastBatchsBytes() {
    org.assertj.core.api.Assertions.assertThat(JetStreamFeed.nextFetch(1000, 0, 0)).as("before any batch").isEqualTo(1000);
    org.assertj.core.api.Assertions.assertThat(JetStreamFeed.nextFetch(1000, 400L * 1000, 1000)).as("one-record wire").isEqualTo(1000);
    org.assertj.core.api.Assertions.assertThat(JetStreamFeed.nextFetch(1000, 12_000L * 1000, 1000)).as("30 records per message")
        .isEqualTo((int) (JetStreamFeed.FETCH_BYTES / 12_000));
    org.assertj.core.api.Assertions.assertThat(JetStreamFeed.nextFetch(1000, 2 * JetStreamFeed.FETCH_BYTES, 1)).as("a message past the aim").isEqualTo(1);
  }

  /** FR-DRS-44: partition p is placed on the tag p mod n; unset, the server places it; a stream is placed when it carries its tag. */
  @Test void aPartitionIsPlacedOnItsServersTag() {
    assertEquals(null, JetStreamFeed.placementTag(null, 3));
    assertEquals(null, JetStreamFeed.placementTag(" ", 3));
    assertEquals("nats-0", JetStreamFeed.placementTag("nats-0,nats-1,nats-2", 0));
    assertEquals("nats-1", JetStreamFeed.placementTag("nats-0,nats-1,nats-2", 4));
    assertEquals("nats-2", JetStreamFeed.placementTag("nats-0, nats-1 , nats-2", 2));
    org.assertj.core.api.Assertions.assertThat(JetStreamFeed.placed(null, null)).as("no placement asked").isTrue();
    org.assertj.core.api.Assertions.assertThat(JetStreamFeed.placed(null, "nats-1")).as("unplaced stream, tag wanted").isFalse();
    var elsewhere = io.nats.client.api.Placement.builder().tags("nats-0").build();
    org.assertj.core.api.Assertions.assertThat(JetStreamFeed.placed(elsewhere, "nats-1")).as("on another server").isFalse();
    org.assertj.core.api.Assertions.assertThat(JetStreamFeed.placed(elsewhere, "nats-0")).as("on its server").isTrue();
  }

  /**
   * FR-DRS-52: unset, TRADERX_FILLS goes to a server holding the fewest partitions, for every partition and server count;
   * FILLS_STREAM_PLACEMENT overrides it. Falsifiable: partition 0's tag (the old placement) puts run 5's fills on nats-0
   * with FEED_P0 and FEED_P5 where nats-3 holds one.
   */
  @Test void theFillsStreamIsPlacedOnALeastLoadedServer() {
    assertEquals("nats-3", JetStreamFeed.fillsPlacementTag(null, "nats-0,nats-1,nats-2,nats-3,nats-4", 8));
    assertEquals("nats-4", JetStreamFeed.fillsPlacementTag(" nats-4 ", "nats-0,nats-1,nats-2,nats-3,nats-4", 8));
    assertEquals(null, JetStreamFeed.fillsPlacementTag(null, null, 8));
    for (int n = 1; n <= 8; n++) {
      String tags = java.util.stream.IntStream.range(0, n).mapToObj(i -> "nats-" + i).collect(java.util.stream.Collectors.joining(","));
      for (int partitions = 1; partitions <= 16; partitions++) {
        java.util.Map<String, Integer> held = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) { held.put("nats-" + i, 0); }
        for (int p = 0; p < partitions; p++) { held.merge(JetStreamFeed.placementTag(tags, p), 1, Integer::sum); }
        String fills = JetStreamFeed.fillsPlacementTag("", tags, partitions);
        assertEquals(java.util.Collections.min(held.values()), held.get(fills), n + " servers, " + partitions + " partitions: " + fills + " of " + held);
      }
    }
  }

  /** FR-DRS-50: a feed that fails before it handed anything over resumes at its own start, and says so. */
  @Test void aResumeBeforeAnythingWasHandedOverNamesTheStartNotZero() {
    assertEquals("1333923", JetStreamFeed.resumeAt(JetStreamFeed.fromSequence("/trades.0", 5, 100), 1333922));
    assertEquals("1246538 (its start)", JetStreamFeed.resumeAt(JetStreamFeed.fromSequence("/trades.0", 1246538, 100), -1));
    assertEquals("its start (all)", JetStreamFeed.resumeAt(JetStreamFeed.fromSequence("/trades.1", 1, 100), -1));
    assertEquals("the ack floor + 1 of durable tp-orders-p1", JetStreamFeed.resumeAt(JetStreamFeed.durable("tp-orders-p1", "/orders.1"), -1));
  }
}
