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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * State 009b (FR-09B08): the trade ticket's market trades enter the LMAX sequencer instead of
 * the {@code /trades} bus. trade-service stays the validating gateway edge — it still checks the
 * instrument (reference-data) and account (account-service) exactly as in 009 — but on success it
 * forwards the validated trade to the order-matcher gateway, where it is sequenced as a
 * TRADE_NEW event and booked + position-kept by the single-writer BLP. trade-processor is no
 * longer fed on this path (it stays deployed but idle). The REST request/response contract
 * (POST /trade/, 200 with the echoed TradeOrder) is unchanged (FR-09B40).
 *
 * <p>YU16 (FR-CDM16/21, source FR-01614 adapted): the instrument check resolves the full CDM
 * record from {@code /instruments/{instrumentKey}} instead of a status-only probe of
 * {@code /stocks/{ticker}} — and for a {@code UST-}-routed Treasury it validates the order
 * content (face minimum/increment, maturity) before anything reaches the sequencer. Validation
 * failures surface as {@code {"detail": message}} (see {@link ApiExceptionHandler}).
 */
@RestController
@RequestMapping(value = "/trade", produces = "application/json")
public class TradeOrderController {

  private static final Logger log = LoggerFactory.getLogger(TradeOrderController.class);

  static final String MSG_FACE_MIN = "Bond quantity must be at least 100.";
  static final String MSG_FACE_MULTIPLE = "Bond quantity must be a multiple of 100.";
  static final String MSG_MATURED = "Bond has matured; no new activity is accepted.";
  static final String MSG_METADATA_UNAVAILABLE = "Bond reference metadata is unavailable";

  /**
   * Key prefixes that ROUTE an order into bond validation. Syntactic and deliberately cheap — the
   * authoritative answer is {@link Security#isBond()} on the resolved record, checked immediately
   * below, so a key that merely looks like a bond is refused rather than trusted. Declared here
   * so adding an asset class is one edit rather than a hunt through string literals.
   */
  private static final String[] BOND_KEY_PREFIXES = { "UST-", "CORP-" };

  private static boolean isBondKey(String security) {
    if (security == null) {
      return false;
    }
    for (String prefix : BOND_KEY_PREFIXES) {
      if (security.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

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

    Security instrument = fetchInstrument(tradeOrder.getSecurity());
    if (instrument == null) {
      throw new ResourceNotFoundException(tradeOrder.getSecurity() + " not found in Reference data service.");
    } else if (!validateAccount(tradeOrder.getAccountId())) {
      throw new ResourceNotFoundException(tradeOrder.getAccountId() + " not found in Account service.");
    } else {
      validateBondOrder(tradeOrder, instrument);
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
   * Only a 404 means "no such instrument". Every other 4xx propagates, as 5xx already did —
   * collapsing them told the trader their symbol did not exist when reference-data had actually
   * refused the request, which is a misleading rejection and hides the fault. The key is passed
   * as a URI VARIABLE so a symbol carrying a path or query delimiter is carried by the request
   * rather than changing it.
   *
   * <p>YU16: resolves the CDM record ({@code /instruments/{key}}) rather than probing
   * {@code /stocks/{ticker}} for a status code — the record is what Treasury validation needs.
   */
  private Security fetchInstrument(String instrumentKey) {
    String url = this.referenceDataServiceAddress + "/instruments/{instrumentKey}";
    try {
      ResponseEntity<Security> response = this.restTemplate.getForEntity(url, Security.class, instrumentKey);
      log.info("Validate instrument {}", response.getBody());
      return response.getBody();
    } catch (HttpClientErrorException.NotFound ex) {
      log.info("{} not found in reference data service.", instrumentKey);
      return null;
    }
  }

  /**
   * YU16 (FR-CDM16/21/23): order-content validation for BONDS, fail closed. A bond-shaped key
   * routes an order here; the resolved record must confirm it really is debt — a prefix-shaped
   * key whose metadata says otherwise is refused, never forwarded on the strength of its name.
   * Face must be a positive multiple of 100, at least 100; a matured bond takes no new activity.
   * Non-bond instruments pass through untouched.
   *
   * <p>Widened from Treasuries to ALL debt when corporates arrived. The face-amount rule was
   * keyed on {@code isTreasury()}, so a corporate order for 50 face was ACCEPTED — the system
   * stated a rule and did not enforce it. Note the asymmetry with the book grid, which is
   * deliberately still Treasury-only: a coarse grid is a capability limit and honest, whereas
   * this was a validation gap.
   */
  private void validateBondOrder(TradeOrder tradeOrder, Security instrument) {
    boolean routedAsBond = isBondKey(tradeOrder.getSecurity());
    if (!routedAsBond && !instrument.isBond()) {
      return;
    }
    if (!instrument.isBond()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, MSG_METADATA_UNAVAILABLE);
    }
    if (instrument.isMatured()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, MSG_MATURED);
    }
    Integer quantity = tradeOrder.getQuantity();
    int face = quantity == null ? 0 : quantity;
    if (face < 100) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, MSG_FACE_MIN);
    }
    if (face % 100 != 0) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, MSG_FACE_MULTIPLE);
    }
  }

  /** Same rule as fetchInstrument: only a 404 is "no such account"; anything else is a real fault. */
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
