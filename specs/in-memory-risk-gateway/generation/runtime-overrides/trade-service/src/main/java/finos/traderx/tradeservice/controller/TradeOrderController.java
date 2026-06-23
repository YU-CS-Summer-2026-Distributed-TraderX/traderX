package finos.traderx.tradeservice.controller;

import finos.traderx.messaging.Publisher;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory-risk-gateway: trade-service no longer performs blocking account/reference lookups.
 * The order-matcher Gateway screens local replicas and returns only after its authoritative path
 * accepts or rejects the submitted market trade.
 */
@RestController
@RequestMapping(value = "/trade", produces = "application/json")
public class TradeOrderController {

  private static final Logger log = LoggerFactory.getLogger(TradeOrderController.class);
  private static final AtomicLong CLIENT_SEQUENCE = new AtomicLong();

  // Retained for bean compatibility; the /trades publish is gone in 009b (booking moved to
  // the sequencer), so this publisher is intentionally unused on the create path.
  private final Publisher<TradeOrder> tradePublisher;
  private final RestTemplate restTemplate = new RestTemplate();

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

    if (tradeOrder.getClientOrderId() == null || tradeOrder.getClientOrderId().isBlank()) {
      String existing = tradeOrder.getId();
      tradeOrder.setClientOrderId(existing == null || existing.isBlank()
          ? "trade-gateway-" + CLIENT_SEQUENCE.incrementAndGet()
          : existing);
    }
    log.info("Forwarding market trade to in-memory risk Gateway {}", tradeOrder);
    forwardToSequencer(tradeOrder);
    return ResponseEntity.ok(tradeOrder);
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

  private static String trimTrailingSlash(String url) {
    if (url == null || url.isEmpty()) {
      return "";
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
