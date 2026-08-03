package finos.traderx.algoengine.fills;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.algoengine.service.AlgoOrderService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** research.md Decision 5: correlation-by-orderId logic, tested without a live NATS connection. */
class OrderUpdateSubscriberTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void routesMatchingOrderIdToAlgoOrderService() throws Exception {
    AlgoOrderService service = mock(AlgoOrderService.class);
    OrderUpdateSubscriber subscriber = new OrderUpdateSubscriber("nats://unused:4222", service);

    // Wrapped in a NatsEnvelope, matching what NatsJSONPublisher actually puts on the wire.
    JsonNode envelope = mapper.readTree(
        "{\"topic\":\"/accounts/22214/orders\",\"payload\":{\"orderId\":\"child-0\","
            + "\"remainingQuantity\":0,\"lastExecutionPrice\":\"100.10\"},\"from\":\"order-matcher\"}");
    subscriber.handle(envelope);

    verify(service).onOrderUpdate(eq("child-0"), eq(0), eq(new BigDecimal("100.10")));
  }

  @Test
  void ignoresMessagesWithNoOrderId() throws Exception {
    AlgoOrderService service = mock(AlgoOrderService.class);
    OrderUpdateSubscriber subscriber = new OrderUpdateSubscriber("nats://unused:4222", service);

    JsonNode envelope = mapper.readTree("{\"topic\":\"/accounts/22214/positions\",\"payload\":{\"accountId\":22214}}");
    subscriber.handle(envelope);

    verify(service, never()).onOrderUpdate(any(), any(), any());
  }
}
