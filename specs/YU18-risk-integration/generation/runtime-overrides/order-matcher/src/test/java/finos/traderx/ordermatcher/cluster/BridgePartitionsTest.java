package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BridgePartitionsTest {
  @Test void onePartitionIsTheBareSubjectAndMoreAreSuffixedByAccount() {
    String[] one = BridgePartitions.subjects("/trades", 1);
    assertArrayEquals(new String[] {"/trades"}, one);
    assertEquals("/trades", BridgePartitions.subjectFor(one, 12345));
    String[] four = BridgePartitions.subjects("/orders", 4);
    assertArrayEquals(new String[] {"/orders.0", "/orders.1", "/orders.2", "/orders.3"}, four);
    assertEquals("/orders.1", BridgePartitions.subjectFor(four, 5));
    assertEquals("/orders.3", BridgePartitions.subjectFor(four, -1)); // floorMod, never negative
  }
}
