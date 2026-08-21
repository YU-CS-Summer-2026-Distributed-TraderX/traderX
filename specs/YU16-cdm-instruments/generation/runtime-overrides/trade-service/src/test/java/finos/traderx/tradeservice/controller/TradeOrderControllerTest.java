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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

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
  private static final String EQUITY_JSON =
      "{\"instrumentKey\":\"AAPL\",\"displayName\":\"Apple Inc.\",\"assetClass\":\"Stock\","
      + "\"securityType\":\"Equity\",\"matured\":false}";
  private static final String TREASURY_JSON =
      "{\"instrumentKey\":\"UST-20280630\",\"displayName\":\"U.S. Treasury Note 4.125% due June 30, 2028\","
      + "\"assetClass\":\"US_TREASURY\",\"securityType\":\"Debt\",\"matured\":false,"
      + "\"debtEconomics\":{\"maturityDate\":\"2028-06-30\"}}";
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
    http.expect(requestTo(REF_DATA + "/instruments/AAPL"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(EQUITY_JSON, MediaType.APPLICATION_JSON));
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
    http.expect(requestTo(REF_DATA + "/instruments/AAPL"))
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
    http.expect(requestTo(REF_DATA + "/instruments/AAPL"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withServerError());

    assertThatThrownBy(() -> controller.createTradeOrder(order()))
        .isInstanceOf(HttpServerErrorException.class)
        .isNotInstanceOf(ResourceNotFoundException.class);

    http.verify();
  }

  /**
   * Only 404 means "no such ticker". Any other 4xx now propagates, exactly as a 5xx does — a 401 or
   * 403 from reference-data is a fault, not an invalid symbol, and telling a trader their good
   * symbol does not exist both misdiagnoses the incident and hides it. Fixed 2026-08-02; this test
   * previously asserted the opposite and was written to fail when the fix landed.
   */
  @Test
  void aNon404ClientErrorFromReferenceDataIsSurfacedRatherThanReadAsUnknownTicker() {
    http.expect(requestTo(REF_DATA + "/instruments/AAPL"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> controller.createTradeOrder(order()))
        .isInstanceOf(HttpClientErrorException.class)
        .isNotInstanceOf(ResourceNotFoundException.class);

    http.verify();
  }

  // ---------------------------------------------------------------------------------------------
  // YU16 (FR-CDM16/21/23): Treasury order-content validation at this edge, fail closed.

  private static TradeOrder treasuryOrder(int face) {
    return new TradeOrder("ord-ust", 17017, "UST-20280630", TradeSide.Buy, face);
  }

  private static final String CORPORATE_JSON =
      "{\"instrumentKey\":\"CORP-GS-20360315\",\"displayName\":\"The Goldman Sachs Group 5.750% due March 15, 2036\","
      + "\"assetClass\":\"CORPORATE_BOND\",\"securityType\":\"Debt\",\"matured\":false,"
      + "\"debtEconomics\":{\"maturityDate\":\"2036-03-15\"}}";

  private static TradeOrder corporateOrder(int face) {
    return new TradeOrder("ord-corp", 17017, "CORP-GS-20360315", TradeSide.Buy, face);
  }

  private void expectCorporateFound() {
    http.expect(requestTo(REF_DATA + "/instruments/CORP-GS-20360315"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(CORPORATE_JSON, MediaType.APPLICATION_JSON));
  }

  private void expectTreasuryFound() {
    http.expect(requestTo(REF_DATA + "/instruments/UST-20280630"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(TREASURY_JSON, MediaType.APPLICATION_JSON));
  }

  private void expectTreasuryAccountFound() {
    http.expect(requestTo(ACCOUNTS + "/account/17017"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"id\":17017}", MediaType.APPLICATION_JSON));
  }

  @Test
  void aValidTreasuryOrderIsForwardedToTheSequencer() {
    expectTreasuryFound();
    expectTreasuryAccountFound();
    http.expect(requestTo(MATCHER + "/trades"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess());

    ResponseEntity<TradeOrder> response = controller.createTradeOrder(treasuryOrder(100_000));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    http.verify();
  }

  @Test
  void aFaceBelowTheMinimumIsRejectedWithTheExactMessageAndNeverReachesTheSequencer() {
    expectTreasuryFound();
    expectTreasuryAccountFound();

    assertThatThrownBy(() -> controller.createTradeOrder(treasuryOrder(50)))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("reason", "Bond quantity must be at least 100.");

    http.verify();
  }

  @Test
  void aFaceThatIsNotAMultipleOf100IsRejectedWithTheExactMessage() {
    expectTreasuryFound();
    expectTreasuryAccountFound();

    assertThatThrownBy(() -> controller.createTradeOrder(treasuryOrder(150)))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("reason", "Bond quantity must be a multiple of 100.");

    http.verify();
  }

  // ----- FR-CDM16 widened from Treasuries to all debt --------------------------------------
  //
  // THE GAP THIS CLOSES. The face rule was keyed on isTreasury(), so a corporate order for 50
  // face was ACCEPTED and forwarded to the sequencer. That is different in kind from the book
  // grid staying Treasury-only: a coarse grid refuses to do something, this failed to refuse
  // something the system's own rules call invalid.

  @Test
  void aCorporateFaceBelowTheMinimumIsNowRejectedAndNeverReachesTheSequencer() {
    expectCorporateFound();
    expectTreasuryAccountFound();

    // Before the widening this returned 200 and forwarded. The http.verify() below is the half
    // that matters: no POST to the sequencer was expected, so a forwarded order fails the test
    // rather than merely returning the wrong status.
    assertThatThrownBy(() -> controller.createTradeOrder(corporateOrder(50)))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("reason", "Bond quantity must be at least 100.");

    http.verify();
  }

  @Test
  void aCorporateFaceThatIsNotAMultipleOf100IsNowRejected() {
    expectCorporateFound();
    expectTreasuryAccountFound();

    assertThatThrownBy(() -> controller.createTradeOrder(corporateOrder(150)))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("reason", "Bond quantity must be a multiple of 100.");

    http.verify();
  }

  @Test
  void aLegalCorporateFaceStillReachesTheSequencer() {
    // THE NEGATIVE CONTROL FOR THE TWO ABOVE. A validator that refused every corporate would
    // satisfy both of them and be catastrophically wrong. This is the case that must still pass.
    expectCorporateFound();
    expectTreasuryAccountFound();
    http.expect(requestTo(MATCHER + "/trades"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess());

    ResponseEntity<TradeOrder> response = controller.createTradeOrder(corporateOrder(100_000));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    http.verify();
  }

  @Test
  void anEquityIsUntouchedByTheBondRule() {
    // The other direction: widening the predicate must not start applying a FACE rule to shares.
    // An equity order for 50 shares is perfectly legal and must still book.
    http.expect(requestTo(REF_DATA + "/instruments/IBM"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(
            "{\"instrumentKey\":\"IBM\",\"displayName\":\"IBM\",\"assetClass\":\"Stock\","
            + "\"securityType\":\"Equity\",\"matured\":false}", MediaType.APPLICATION_JSON));
    expectTreasuryAccountFound();
    http.expect(requestTo(MATCHER + "/trades"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess());

    ResponseEntity<TradeOrder> response = controller.createTradeOrder(
        new TradeOrder("ord-eq", 17017, "IBM", TradeSide.Buy, 50));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    http.verify();
  }

  @Test
  void aMaturedTreasuryTakesNoNewActivity() {
    http.expect(requestTo(REF_DATA + "/instruments/UST-20280630"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(TREASURY_JSON.replace("\"matured\":false", "\"matured\":true"),
            MediaType.APPLICATION_JSON));
    expectTreasuryAccountFound();

    assertThatThrownBy(() -> controller.createTradeOrder(treasuryOrder(100_000)))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

    http.verify();
  }

  @Test
  void aUstRoutedKeyWhoseMetadataIsNotATreasuryIsRefusedNotForwardedOnItsName() {
    // The prefix routes; the metadata authorizes (FR-CDM23). An equity-shaped record under a
    // UST- key is a reference-data inconsistency, and the order fails closed.
    http.expect(requestTo(REF_DATA + "/instruments/UST-20280630"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(EQUITY_JSON, MediaType.APPLICATION_JSON));
    expectTreasuryAccountFound();

    assertThatThrownBy(() -> controller.createTradeOrder(treasuryOrder(100_000)))
        .isInstanceOf(ResponseStatusException.class)
        .hasFieldOrPropertyWithValue("reason", "Bond reference metadata is unavailable");

    http.verify();
  }

  /** The same rule on the account leg: a refusal is not "no such account". */
  @Test
  void aNon404ClientErrorFromAccountServiceIsSurfacedRatherThanReadAsUnknownAccount() {
    expectTickerFound();
    http.expect(requestTo(ACCOUNTS + "/account/44044"))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> controller.createTradeOrder(order()))
        .isInstanceOf(HttpClientErrorException.class)
        .isNotInstanceOf(ResourceNotFoundException.class);

    http.verify();
  }
}
