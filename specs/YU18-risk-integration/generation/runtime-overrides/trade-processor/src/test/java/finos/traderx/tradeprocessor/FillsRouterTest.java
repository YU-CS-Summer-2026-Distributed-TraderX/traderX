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
