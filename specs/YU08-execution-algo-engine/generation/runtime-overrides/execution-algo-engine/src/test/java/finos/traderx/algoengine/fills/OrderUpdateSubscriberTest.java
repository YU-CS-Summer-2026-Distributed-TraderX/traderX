package finos.traderx.algoengine.fills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.algoengine.eventstore.AlgoEventStore;
import finos.traderx.algoengine.model.AlgoType;
import finos.traderx.algoengine.model.Bucket;
import finos.traderx.algoengine.model.OrderSide;
import finos.traderx.algoengine.model.ParentOrder;
import finos.traderx.algoengine.model.ParentOrderStatus;
import finos.traderx.algoengine.orders.OrderMatcherClient;
import finos.traderx.algoengine.orders.PriceClient;
import finos.traderx.algoengine.service.AlgoOrderService;
import finos.traderx.algoengine.volume.VolumeProfileSource;
import io.nats.client.impl.NatsMessage;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * research.md Decision 5: correlation logic, tested without a live NATS connection.
 *
 * <p>The cases from {@code cluster-bridge} down are the regression set for
 * `algo-engine-never-sees-child-fills-on-the-cluster-tier`: this subscriber has to accept the
 * cluster tier's shape (bare {@code /orders}, id in {@code id}, epoch-qualified
 * {@code <epoch>-<orderRef>}) as well as the single-BLP tier's ({@code /accounts/<id>/orders}, id
 * in {@code orderId}, {@code ord-013-NNNN}). The two that matter most are the ones that drive a
 * REAL {@link AlgoOrderService} rather than a mock — a mock verifies the parser, only the real
 * service proves the join actually lands on the right bucket, and only the negative control proves
 * a fix that marked everything filled would fail.
 */
class OrderUpdateSubscriberTest {
  private final ObjectMapper mapper = new ObjectMapper();

  /** Exactly what {@code OrderNatsPublisher.encode} puts on the wire for a filled child, as
   * captured off the bus on the rig 2026-08-19 (parent 8e7e7c9f, child 2549). */
  private static String clusterFillPayload(String id) {
    return "{\"topic\":\"/orders\",\"from\":\"cluster-bridge\",\"type\":\"OrderUpdate\","
        + "\"date\":1755600000000,\"payload\":{\"id\":\"" + id + "\",\"accountId\":22214,"
        + "\"security\":\"IBM\",\"side\":\"Buy\",\"quantity\":10,\"remainingQuantity\":0,"
        + "\"limitPrice\":201.000000,\"status\":\"FILLED\",\"lastExecutionPrice\":196.200000,"
        + "\"lastFillQuantity\":10,\"createdAt\":1755600000000,\"updatedAt\":1755600000000}}";
  }

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

  @Test
  void singleBlpOrderIdsAreNotRewritten() throws Exception {
    AlgoOrderService service = mock(AlgoOrderService.class);
    OrderUpdateSubscriber subscriber = new OrderUpdateSubscriber("nats://unused:4222", service);

    // OrderSnapshot.orderIdFor: "ord-013-%04d". It carries hyphens of its own and must survive the
    // epoch-prefix strip untouched, or fixing the cluster tier would break the tier that worked.
    JsonNode envelope = mapper.readTree(
        "{\"topic\":\"/accounts/22214/orders\",\"payload\":{\"orderId\":\"ord-013-0042\","
            + "\"remainingQuantity\":0,\"lastExecutionPrice\":\"182.16\"}}");
    subscriber.handle(envelope);

    verify(service).onOrderUpdate(eq("ord-013-0042"), eq(0), eq(new BigDecimal("182.16")));
  }

  @Test
  void clusterBridgeIdIsStrippedOfItsEpochPrefix() throws Exception {
    AlgoOrderService service = mock(AlgoOrderService.class);
    OrderUpdateSubscriber subscriber = new OrderUpdateSubscriber("nats://unused:4222", service);

    subscriber.handle(mapper.readTree(clusterFillPayload("1-2549")));

    // The engine stores the bare orderRef the gateway's POST response returned.
    verify(service).onOrderUpdate(eq("2549"), eq(0), eq(new BigDecimal("196.2")));
  }

  @Test
  void clusterBridgeZeroExecutionPriceReadsAsNoExecution() throws Exception {
    AlgoOrderService service = mock(AlgoOrderService.class);
    OrderUpdateSubscriber subscriber = new OrderUpdateSubscriber("nats://unused:4222", service);

    // A resting child: the bridge renders Px.NONE as 0.000000 rather than omitting the field.
    subscriber.handle(mapper.readTree(
        "{\"topic\":\"/orders\",\"payload\":{\"id\":\"1-2549\",\"remainingQuantity\":10,"
            + "\"status\":\"NEW\",\"lastExecutionPrice\":0.000000,\"lastFillQuantity\":0}}"));

    verify(service).onOrderUpdate(eq("2549"), eq(10), (BigDecimal) org.mockito.ArgumentMatchers.isNull());
  }

  @Test
  void bareOrdersSubjectIsAccepted() {
    AlgoOrderService service = mock(AlgoOrderService.class);
    OrderUpdateSubscriber subscriber = new OrderUpdateSubscriber("nats://unused:4222", service);

    subscriber.onMessage(message("/orders", clusterFillPayload("1-2549")));

    verify(service).onOrderUpdate(eq("2549"), eq(0), eq(new BigDecimal("196.2")));
  }

  @Test
  void accountScopedOrdersSubjectIsStillAccepted() {
    AlgoOrderService service = mock(AlgoOrderService.class);
    OrderUpdateSubscriber subscriber = new OrderUpdateSubscriber("nats://unused:4222", service);

    subscriber.onMessage(message("/accounts/22214/orders",
        "{\"topic\":\"/accounts/22214/orders\",\"payload\":{\"orderId\":\"ord-013-0042\","
            + "\"remainingQuantity\":0,\"lastExecutionPrice\":\"182.16\"}}"));

    verify(service).onOrderUpdate(eq("ord-013-0042"), eq(0), eq(new BigDecimal("182.16")));
  }

  @Test
  void otherSubjectsOnTheCatchAllSubscriptionAreIgnored() {
    AlgoOrderService service = mock(AlgoOrderService.class);
    OrderUpdateSubscriber subscriber = new OrderUpdateSubscriber("nats://unused:4222", service);

    // Everything else this ">" subscription receives. A payload carrying an "id" would otherwise
    // be indistinguishable from an order update once the subject filter widened.
    subscriber.onMessage(message("/accounts/22214/trades",
        "{\"payload\":{\"id\":\"1-2549\",\"remainingQuantity\":0,\"lastExecutionPrice\":196.2}}"));
    subscriber.onMessage(message("/accounts/22214/positions", "{\"payload\":{\"id\":\"1-2549\"}}"));
    subscriber.onMessage(message("/trades", "{\"payload\":{\"id\":\"1-2549\"}}"));

    verify(service, never()).onOrderUpdate(any(), any(), any());
  }

  /** The correlation itself, against a real {@link AlgoOrderService}: a cluster-tier fill for the
   * child this engine actually submitted fills the bucket and completes the parent. This is what
   * the mock-based cases above cannot prove — they prove the parser. */
  @Test
  void clusterFillForOurChildFillsTheBucketAndCompletesTheParent() throws Exception {
    AlgoOrderService service = serviceWithSubmittedChild("2549");
    ParentOrder order = service.all().iterator().next();
    OrderUpdateSubscriber subscriber = new OrderUpdateSubscriber("nats://unused:4222", service);

    subscriber.onMessage(message("/orders", clusterFillPayload("1-2549")));

    Bucket bucket = order.getBuckets().get(0);
    assertTrue(bucket.isFilled(), "the child's own fill must fill its bucket");
    assertEquals(0, bucket.getRemainingQuantity());
    assertEquals(new BigDecimal("196.2"), bucket.getLastExecutionPrice());
    assertEquals(ParentOrderStatus.COMPLETED, service.get(order.getParentOrderId()).getStatus());
  }

  /** Negative control for the case above. A fix that marked every bucket filled would pass every
   * other test in this file; this is the one it fails. */
  @Test
  void clusterFillForSomeoneElsesOrderFillsNothing() throws Exception {
    AlgoOrderService service = serviceWithSubmittedChild("2549");
    ParentOrder order = service.all().iterator().next();
    OrderUpdateSubscriber subscriber = new OrderUpdateSubscriber("nats://unused:4222", service);

    // Same epoch, same shape, different orderRef — another account's order on the shared subject.
    subscriber.onMessage(message("/orders", clusterFillPayload("1-9999")));

    Bucket bucket = order.getBuckets().get(0);
    // Precondition: the bucket really was submitted and really is joinable, so "nothing filled"
    // cannot be a silent setup failure standing in for a verdict about the correlation.
    assertEquals("2549", bucket.getChildOrderId());
    assertFalse(bucket.isFilled(), "a fill for an order this engine did not submit must not fill a bucket");
    assertNull(bucket.getRemainingQuantity());
    assertNull(bucket.getLastExecutionPrice());
    assertEquals(ParentOrderStatus.RUNNING, service.get(order.getParentOrderId()).getStatus());
  }

  /** A real service holding one running TWAP parent whose single bucket has been submitted, with
   * {@code childOrderId} standing in for what the gateway's {@code POST /orders} returned. */
  private AlgoOrderService serviceWithSubmittedChild(String childOrderId) throws Exception {
    PriceClient priceClient = mock(PriceClient.class);
    when(priceClient.currentPrice(anyString())).thenReturn(new BigDecimal("200.00"));
    OrderMatcherClient orderMatcherClient = mock(OrderMatcherClient.class);
    when(orderMatcherClient.submitChildOrder(anyString(), anyInt(), anyString(), any(), anyInt(), any()))
        .thenReturn(childOrderId);
    AlgoOrderService service = new AlgoOrderService(mock(AlgoEventStore.class), priceClient,
        orderMatcherClient, mock(VolumeProfileSource.class), 10, 10);

    service.create(22214, "IBM", OrderSide.Buy, 10, AlgoType.TWAP, 1, 1);
    service.submitDueBuckets(System.currentTimeMillis() + 1);
    return service;
  }

  private static io.nats.client.Message message(String subject, String json) {
    return NatsMessage.builder()
        .subject(subject)
        .data(json.getBytes(StandardCharsets.UTF_8))
        .build();
  }
}
