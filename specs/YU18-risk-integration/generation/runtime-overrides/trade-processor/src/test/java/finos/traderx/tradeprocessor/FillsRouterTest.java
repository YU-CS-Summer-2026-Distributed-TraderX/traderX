package finos.traderx.tradeprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import finos.traderx.tradeprocessor.model.OrderUpdate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-DRS-48: a batch is one message per gateway, the records newline-joined in order (FR-DRS-41's shape); the view
 *  keeps an order's gateway from its booking event across later updates, so those are routed too. */
class FillsRouterTest {
  @Test
  void aBatchJoinsEachGatewaysRecordsInOrder() {
    FillsRouter.Batch b = new FillsRouter.Batch();
    b.add("gw-a", FillsRouter.bytes("{\"a\":1}"));
    b.add("gw-b", FillsRouter.bytes("{\"b\":1}"));
    b.add("gw-a", FillsRouter.bytes("{\"a\":2}"));
    assertThat(new String(FillsRouter.Batch.join(List.of(FillsRouter.bytes("{\"a\":1}"), FillsRouter.bytes("{\"a\":2}"))), StandardCharsets.UTF_8))
        .isEqualTo("{\"a\":1}\n{\"a\":2}");
    assertThat(FillsRouter.Batch.join(List.of(FillsRouter.bytes("x")))).isEqualTo(FillsRouter.bytes("x"));
    assertThat(b.isEmpty()).isFalse();
    assertThat(new FillsRouter.Batch().isEmpty()).isTrue();
    assertThat(FillsRouter.subject("gw-a")).isEqualTo("/fills.gw-a");
  }

  /** FR-DRS-53: the gateway's shard is floorMod(String.hashCode, S). ClusterGatewayMainTest pins the same values: the
   *  router and the gateway must agree, or a gateway reads a stream nothing is routed to. */
  @Test
  void aGatewaysShardIsPinned() {
    assertThat(FillsRouter.shard("gw-a", 20)).isEqualTo(4);
    assertThat(FillsRouter.shard("cluster-gateway-6f8d9c7b5-x2k4q", 20)).isEqualTo(17);   // a negative hashCode
    assertThat(FillsRouter.subject("gw-b", 20)).isEqualTo("/fillsk.5.gw-b");
    assertThat(FillsRouter.subject("gw-b", 1)).isEqualTo("/fills.gw-b");
    // FR-DRS-55: a claimed name carries its stream; RoutedFillsShardTest pins the same
    assertThat(FillsRouter.shard("gw-a_s7", 20)).isEqualTo(7);
    assertThat(FillsRouter.subject("gw-a_s7", 20)).isEqualTo("/fillsk.7.gw-a_s7");
    assertThat(FillsRouter.shard("gw-a_s20", 20)).isEqualTo(Math.floorMod("gw-a_s20".hashCode(), 20));   // outside the count: hashed
    assertThat(FillsRouter.shard("gw-a_sx", 20)).isEqualTo(Math.floorMod("gw-a_sx".hashCode(), 20));
    assertThat(FillsRouter.shard("gw-a_s", 20)).isEqualTo(Math.floorMod("gw-a_s".hashCode(), 20));
  }

  @Test
  void theViewKeepsTheGatewayNamedAtBooking() {
    OrderView view = new OrderView(10);
    OrderUpdate booked = update("7-5", 11, "NEW", 1L, 0); booked.setGateway("gw-a");
    view.apply(booked);
    OrderUpdate fill = update("7-5", 11, "PARTIALLY_FILLED", 2L, 0);   // a fill names no gateway
    view.apply(fill);
    assertThat(view.get(11, "7-5").getGateway()).isEqualTo("gw-a");
    OrderUpdate other = update("7-6", 11, "NEW", 3L, 0);               // booked with no principal: never routed
    view.apply(other);
    assertThat(view.get(11, "7-6").getGateway()).isNull();
  }

  private static OrderUpdate update(String id, int account, String status, long seq, int ordinal) {
    OrderUpdate u = new OrderUpdate();
    u.setId(id); u.setAccountId(account); u.setStatus(status); u.setQuantity(10); u.setRemainingQuantity(5);
    u.setConsensusSequence(seq); u.setOutputOrdinal(ordinal);
    return u;
  }
}
