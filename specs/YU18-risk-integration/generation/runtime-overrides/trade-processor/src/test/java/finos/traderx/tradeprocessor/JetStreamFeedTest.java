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
}
