package finos.traderx.algoengine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finos.traderx.algoengine.eventstore.AlgoEventStore;
import finos.traderx.algoengine.model.AlgoType;
import finos.traderx.algoengine.model.OrderSide;
import finos.traderx.algoengine.model.ParentOrder;
import finos.traderx.algoengine.model.ParentOrderStatus;
import finos.traderx.algoengine.orders.OrderMatcherClient;
import finos.traderx.algoengine.orders.PriceClient;
import finos.traderx.algoengine.volume.VolumeProfileSource;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Discovered live during kind verification: order-matcher can broadcast a child order's fill over
 * NATS before the synchronous {@code POST /orders} response returns (NATS delivery races the HTTP
 * round trip), so {@link AlgoOrderService#onOrderUpdate} can be called with an {@code orderId}
 * this service doesn't know about yet. These tests exercise the fix without NATS/Spring — direct
 * calls to the public methods {@code OrderUpdateSubscriber}/{@code AlgoScheduler} would otherwise
 * make.
 */
class AlgoOrderServiceTest {

  private AlgoOrderService newService(OrderMatcherClient orderMatcherClient) throws Exception {
    AlgoEventStore eventStore = mock(AlgoEventStore.class);
    PriceClient priceClient = mock(PriceClient.class);
    when(priceClient.currentPrice(anyString())).thenReturn(new BigDecimal("100.00"));
    VolumeProfileSource volumeProfileSource = mock(VolumeProfileSource.class);
    return new AlgoOrderService(eventStore, priceClient, orderMatcherClient, volumeProfileSource, 10, 10);
  }

  @Test
  void fillArrivingBeforeSubmitRegistersOnceTheChildOrderIsKnown() throws Exception {
    OrderMatcherClient orderMatcherClient = mock(OrderMatcherClient.class);
    when(orderMatcherClient.submitChildOrder(anyString(), anyInt(), anyString(), any(), anyInt(), any()))
        .thenReturn("child-1");
    AlgoOrderService service = newService(orderMatcherClient);

    ParentOrder order = service.create(22214, "IBM", OrderSide.Buy, 10, AlgoType.TWAP, 1, 1);

    // The fill notification for "child-1" arrives before this engine has even submitted it.
    service.onOrderUpdate("child-1", 0, new BigDecimal("100.00"));

    // Bucket isn't submitted yet, so the early update must not have been dropped or misapplied.
    assertTrue(order.getBuckets().get(0).getRemainingQuantity() == null);

    // Now the scheduler submits the due bucket, which reconciles the stashed update.
    service.submitDueBuckets(System.currentTimeMillis() + 1);

    ParentOrder after = service.get(order.getParentOrderId());
    assertTrue(after.getBuckets().get(0).isFilled());
    assertEquals(ParentOrderStatus.COMPLETED, after.getStatus());
  }

  @Test
  void fillArrivingAfterSubmitStillCorrelates() throws Exception {
    OrderMatcherClient orderMatcherClient = mock(OrderMatcherClient.class);
    when(orderMatcherClient.submitChildOrder(anyString(), anyInt(), anyString(), any(), anyInt(), any()))
        .thenReturn("child-2");
    AlgoOrderService service = newService(orderMatcherClient);

    ParentOrder order = service.create(22214, "IBM", OrderSide.Sell, 5, AlgoType.TWAP, 1, 1);
    service.submitDueBuckets(System.currentTimeMillis() + 1);

    service.onOrderUpdate("child-2", 0, new BigDecimal("50.00"));

    ParentOrder after = service.get(order.getParentOrderId());
    assertTrue(after.getBuckets().get(0).isFilled());
    assertEquals(ParentOrderStatus.COMPLETED, after.getStatus());
  }
}
