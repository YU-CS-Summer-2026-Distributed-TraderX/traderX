package finos.traderx.tradeservice.controller;

import finos.traderx.messaging.Publisher;
import finos.traderx.tradeservice.exceptions.ResourceNotFoundException;
import finos.traderx.tradeservice.model.Account;
import finos.traderx.tradeservice.model.Security;
import finos.traderx.tradeservice.model.TradeOrder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * State 009b (FR-09B08): the trade ticket's market trades enter the LMAX sequencer instead of
 * the {@code /trades} bus. trade-service stays the validating gateway edge — it still checks the
 * ticker (reference-data) and account (account-service) exactly as in 009 — but on success it
 * forwards the validated trade to the order-matcher gateway, where it is sequenced as a
 * TRADE_NEW event and booked + position-kept by the single-writer BLP. trade-processor is no
 * longer fed on this path (it stays deployed but idle). The REST request/response contract
 * (POST /trade/, 200 with the echoed TradeOrder) is unchanged (FR-09B40).
 */
@RestController
@RequestMapping(value = "/trade", produces = "application/json")
public class TradeOrderController {

  private static final Logger log = LoggerFactory.getLogger(TradeOrderController.class);

  // Retained for bean compatibility; the /trades publish is gone in 009b (booking moved to
  // the sequencer), so this publisher is intentionally unused on the create path.
  private final Publisher<TradeOrder> tradePublisher;
  private final RestTemplate restTemplate = new RestTemplate();

  @Value("${reference.data.service.url}")
  private String referenceDataServiceAddress;

  @Value("${account.service.url}")
  private String accountServiceAddress;

  @Value("${order.matcher.url:http://order-matcher:18110}")
  private String orderMatcherAddress;

  public TradeOrderController(Publisher<TradeOrder> tradePublisher) {
    this.tradePublisher = tradePublisher;
  }

  @Operation(description = "Submit a new trade order")
  @PostMapping("/")
  public ResponseEntity<TradeOrder> createTradeOrder(
      @Parameter(description = "the intended trade order") @RequestBody TradeOrder tradeOrder) {
    log.info("Called createTradeOrder");

    if (!validateTicker(tradeOrder.getSecurity())) {
      throw new ResourceNotFoundException(tradeOrder.getSecurity() + " not found in Reference data service.");
    } else if (!validateAccount(tradeOrder.getAccountId())) {
      throw new ResourceNotFoundException(tradeOrder.getAccountId() + " not found in Account service.");
    } else {
      log.info("Trade is valid. Forwarding to sequencer {}", tradeOrder);
      forwardToSequencer(tradeOrder);
      return ResponseEntity.ok(tradeOrder);
    }
  }

  /**
   * Forward the validated market trade to the order-matcher gateway (POST /trades). Booking is
   * asynchronous on the BLP — matching 009's fire-and-forget publish semantics — so we do not
   * block on the booking result; order-matcher's MarketTradeRequest reads the overlapping
   * fields (security, quantity, accountId, side) and ignores the rest.
   */
  private void forwardToSequencer(TradeOrder tradeOrder) {
    String url = trimTrailingSlash(orderMatcherAddress) + "/trades";
    try {
      restTemplate.postForEntity(url, tradeOrder, Void.class);
    } catch (RestClientException ex) {
      throw new RuntimeException("Failed to forward trade order to order-matcher sequencer", ex);
    }
  }

  /**
   * Only a 404 means "no such ticker". Every other 4xx propagates, as 5xx already did — collapsing
   * them told the trader their symbol did not exist when reference-data had actually refused the
   * request, which is a misleading rejection and hides the fault. The ticker is passed as a URI
   * VARIABLE so a symbol carrying a path or query delimiter is carried by the request rather than
   * changing it.
   */
  private boolean validateTicker(String ticker) {
    String url = this.referenceDataServiceAddress + "/stocks/{ticker}";
    try {
      ResponseEntity<Security> response = this.restTemplate.getForEntity(url, Security.class, ticker);
      log.info("Validate ticker {}", response.getBody());
      return true;
    } catch (HttpClientErrorException.NotFound ex) {
      log.info("{} not found in reference data service.", ticker);
      return false;
    }
  }

  /** Same rule as validateTicker: only a 404 is "no such account"; anything else is a real fault. */
  private boolean validateAccount(Integer id) {
    String url = this.accountServiceAddress + "/account/{id}";
    try {
      ResponseEntity<Account> response = this.restTemplate.getForEntity(url, Account.class, id);
      log.info("Validate account {}", response.getBody());
      return true;
    } catch (HttpClientErrorException.NotFound ex) {
      log.info("Account {} not found in account service.", id);
      return false;
    }
  }

  private static String trimTrailingSlash(String url) {
    if (url == null || url.isEmpty()) {
      return "";
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
