package finos.traderx.tradeservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Publisher;
import finos.traderx.tradeservice.exceptions.ResourceNotFoundException;
import finos.traderx.tradeservice.model.TradeOrder;
import finos.traderx.tradeservice.model.TradeSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Unit tests for the order-admission gate — no Spring context. The controller validates the
 * ticker (reference-data) and account (account-service) over HTTP before publishing. We bind a
 * {@link MockRestServiceServer} to the controller's RestTemplate and mock the Publisher, so the
 * whole gate runs in-process. The load-bearing assertions are the REJECT paths: when a downstream
 * says 404, the order must be signalled as not-found AND must never reach the trade feed.
 */
@ExtendWith(MockitoExtension.class)
class TradeOrderControllerTest {

  private static final String REF_URL = "http://ref-data:18085";
  private static final String ACCT_URL = "http://account:18088";

  @Mock private Publisher<TradeOrder> tradePublisher;

  private TradeOrderController controller;
  private MockRestServiceServer downstream;

  @BeforeEach
  void setUp() {
    controller = new TradeOrderController(tradePublisher);
    RestTemplate restTemplate = new RestTemplate();
    ReflectionTestUtils.setField(controller, "restTemplate", restTemplate);
    ReflectionTestUtils.setField(controller, "referenceDataServiceAddress", REF_URL);
    ReflectionTestUtils.setField(controller, "accountServiceAddress", ACCT_URL);
    downstream = MockRestServiceServer.createServer(restTemplate);
  }

  private static TradeOrder order() {
    return new TradeOrder("ord-1", 42, "AAPL", TradeSide.Buy, 100);
  }

  @Test
  void publishesAndReturnsOrder_whenTickerAndAccountValid() throws Exception {
    downstream.expect(requestTo(REF_URL + "/stocks/AAPL"))
        .andRespond(withSuccess("{\"ticker\":\"AAPL\"}", MediaType.APPLICATION_JSON));
    downstream.expect(requestTo(ACCT_URL + "/account/42"))
        .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));

    TradeOrder submitted = order();
    assertThat(controller.createTradeOrder(submitted).getBody()).isSameAs(submitted);

    verify(tradePublisher).publish("/trades", submitted);
    downstream.verify();
  }

  @Test
  void rejectsAndDoesNotPublish_whenTickerUnknown() throws Exception {
    downstream.expect(requestTo(REF_URL + "/stocks/AAPL"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThatThrownBy(() -> controller.createTradeOrder(order()))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("AAPL");

    verify(tradePublisher, never()).publish(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsAndDoesNotPublish_whenAccountUnknown() throws Exception {
    downstream.expect(requestTo(REF_URL + "/stocks/AAPL"))
        .andRespond(withSuccess("{\"ticker\":\"AAPL\"}", MediaType.APPLICATION_JSON));
    downstream.expect(requestTo(ACCT_URL + "/account/42"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThatThrownBy(() -> controller.createTradeOrder(order()))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("42");

    verify(tradePublisher, never()).publish(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void surfacesFailure_whenPublishThrows() throws Exception {
    downstream.expect(requestTo(REF_URL + "/stocks/AAPL"))
        .andRespond(withSuccess("{\"ticker\":\"AAPL\"}", MediaType.APPLICATION_JSON));
    downstream.expect(requestTo(ACCT_URL + "/account/42"))
        .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));
    TradeOrder submitted = order();
    org.mockito.Mockito.doThrow(new PubSubException("feed down"))
        .when(tradePublisher).publish(org.mockito.ArgumentMatchers.eq("/trades"),
            org.mockito.ArgumentMatchers.same(submitted));

    // A publish failure must not be swallowed — the caller has to learn the trade did not go out.
    assertThatThrownBy(() -> controller.createTradeOrder(submitted))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Failed to publish");
  }
}
