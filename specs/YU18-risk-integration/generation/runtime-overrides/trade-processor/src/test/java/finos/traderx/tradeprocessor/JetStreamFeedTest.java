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
}
