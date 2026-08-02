package finos.traderx.tradeservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import finos.traderx.messaging.Publisher;
import finos.traderx.tradeservice.exceptions.ResourceNotFoundException;
import finos.traderx.tradeservice.model.TradeOrder;
import finos.traderx.tradeservice.model.TradeSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Unit tests for the trade ticket's validating edge.
 *
 * <p><b>Why this class exists at all.</b> trade-service shipped with exactly one test, a
 * {@code @SpringBootTest} whose context cannot start without a live NATS broker (the publisher bean
 * dials {@code nats.address} during bean creation), and it sat in {@code src/main/test/java} — a
 * directory Gradle does not compile. The module therefore reported {@code BUILD SUCCESSFUL} while
 * executing nothing, and CI excluded it deliberately rather than run a module that proves nothing.
 * These tests live in the standard {@code src/test/java}, construct the controller directly, and
 * touch neither a broker nor a network, so they run in the ordinary unit tier.
 *
 * <p><b>What is actually being pinned.</b> This controller is the validating gateway edge: it checks
 * the ticker against reference-data and the account against account-service, and only then forwards
 * to the sequencer. The order of those checks is a real contract — an unknown ticker must not cost
 * an account lookup, and neither failure may reach the sequencer — so the tests assert on the calls
 * that were and were not made, not merely on the status code returned.
 */
class TradeOrderControllerTest {

  private static final String REF_DATA = "http://reference-data:18085";
  private static final String ACCOUNTS = "http://account-service:18088";
  private static final String MATCHER = "http://order-matcher:18110";

  private Publisher<TradeOrder> tradePublisher;
  private TradeOrderController controller;
  private MockRestServiceServer http;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    tradePublisher = mock(Publisher.class);
    controller = new TradeOrderController(tradePublisher);
    setAddresses(MATCHER);
  }

  /**
   * The three {@code @Value} fields are injected by Spring in production; with no context running,
   * they are set directly. The RestTemplate is created inline by the controller rather than
   * injected, so the mock server is bound to that instance — which is what keeps the whole suite
   * off the network without changing production code to suit the test.
   */
  private void setAddresses(String matcherUrl) {
    ReflectionTestUtils.setField(controller, "referenceDataServiceAddress", REF_DATA);
    ReflectionTestUtils.setField(controller, "accountServiceAddress", ACCOUNTS);
    ReflectionTestUtils.setField(controller, "orderMatcherAddress", matcherUrl);
    RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(controller, "restTemplate");
    http = MockRestServiceServer.bindTo(restTemplate).build();
  }

  private static TradeOrder order() {
    return new TradeOrder("ord-1", 44044, "AAPL", TradeSide.Buy, 100);
  }

  private void expectTickerFound() {
    http.expect(requestTo(REF_DATA + "/stocks/AAPL"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"ticker\":\"AAPL\"}", MediaType.APPLICATION_JSON));
  }

  private void expectAccountFound() {
    http.expect(requestTo(ACCOUNTS + "/account/44044"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"id\":44044}", MediaType.APPLICATION_JSON));
  }

  @Test
  void aValidOrderIsForwardedToTheSequencerExactlyOnceAndEchoedBack() {
    expectTickerFound();
    expectAccountFound();
    http.expect(requestTo(MATCHER + "/trades"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess());

    ResponseEntity<TradeOrder> response = controller.createTradeOrder(order());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getSecurity()).isEqualTo("AAPL");
    // Every expected call happened, and nothing else did.
    http.verify();
    // The publisher is retained for bean compatibility only: booking moved to the sequencer, so a
    // publish here would double-book the trade on the old /trades bus.
    verifyNoInteractions(tradePublisher);
  }

  @Test
  void anUnknownTickerIsRejectedWithoutLookingUpTheAccountOrCallingTheSequencer() {
    http.expect(requestTo(REF_DATA + "/stocks/AAPL"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThatThrownBy(() -> controller.createTradeOrder(order()))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("AAPL");

    // The account lookup and the sequencer POST were never declared, so the mock server would have
    // failed the test had either been attempted. verify() confirms the ticker call did happen.
    http.verify();
    verifyNoInteractions(tradePublisher);
  }

  @Test
  void anUnknownAccountIsRejectedWithoutCallingTheSequencer() {
    expectTickerFound();
    http.expect(requestTo(ACCOUNTS + "/account/44044"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    assertThatThrownBy(() -> controller.createTradeOrder(order()))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("44044");

    http.verify();
    verifyNoInteractions(tradePublisher);
  }

  @Test
  void aTrailingSlashOnTheMatcherUrlDoesNotProduceADoubleSlash() {
    controller = new TradeOrderController(tradePublisher);
    setAddresses(MATCHER + "/");
    expectTickerFound();
    expectAccountFound();
    // Exact URL match: "http://order-matcher:18110//trades" would fail this expectation, and on a
    // real gateway a double slash is a different path rather than a forgiving alias.
    http.expect(requestTo(MATCHER + "/trades"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess());

    controller.createTradeOrder(order());

    http.verify();
  }

  @Test
  void aSequencerFailureIsSurfacedRatherThanReportedAsAcceptance() {
    expectTickerFound();
    expectAccountFound();
    http.expect(requestTo(MATCHER + "/trades"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withServerError());

    // The caller must not receive a 200 for a trade the sequencer never took. Fire-and-forget
    // applies to the BOOKING RESULT, which is asynchronous on the engine — not to the handoff
    // itself, which either happened or did not.
    assertThatThrownBy(() -> controller.createTradeOrder(order()))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Failed to forward trade order");

    http.verify();
  }

  /**
   * A reference-data OUTAGE is surfaced, not disguised as an invalid symbol.
   *
   * <p>The distinction is load-bearing and easy to get backwards: the validator catches
   * {@code HttpClientErrorException}, which is 4xx only, so a 5xx propagates untouched. A trader
   * whose order is rejected therefore learns that the platform is unwell, rather than being told
   * their perfectly good symbol does not exist. This test was originally written asserting the
   * opposite, on the assumption that any failed lookup collapsed into "not found" — the run
   * disproved it, which is the entire reason the case is pinned here.
   */
  @Test
  void aReferenceDataOutageIsSurfacedRatherThanReportedAsAnUnknownTicker() {
    http.expect(requestTo(REF_DATA + "/stocks/AAPL"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withServerError());

    assertThatThrownBy(() -> controller.createTradeOrder(order()))
        .isInstanceOf(HttpServerErrorException.class)
        .isNotInstanceOf(ResourceNotFoundException.class);

    http.verify();
  }

  /**
   * Pins CURRENT behaviour for the one case that IS conflated, called out rather than hidden.
   *
   * <p>Only 404 means "no such ticker", but every 4xx is treated as one. A 401 or 403 from
   * reference-data — an expired credential, a policy change — is reported to the trader as an
   * unknown symbol, which is a misleading diagnosis and sends the investigation to the wrong team.
   * The blast radius is far narrower than a full outage (see the test above), which is why this is
   * recorded rather than fixed in passing.
   *
   * <p>If the validator is later narrowed to 404 alone, this test SHOULD fail — read that as the
   * fix landing, not as a break.
   */
  @Test
  void aNon404ClientErrorFromReferenceDataIsCollapsedIntoUnknownTicker() {
    http.expect(requestTo(REF_DATA + "/stocks/AAPL"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> controller.createTradeOrder(order()))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("AAPL");

    http.verify();
  }
}
