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
}
