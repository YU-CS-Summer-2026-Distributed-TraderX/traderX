package finos.traderx.ordermatcher.controller;

import finos.traderx.ordermatcher.api.MarketTradeRequest;
import finos.traderx.ordermatcher.service.OrderMatcherService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-trade ingress for the LMAX gateway (FR-09B08). trade-service validates the ticker and
 * account, then forwards the trade here; the gateway sequences it as a TRADE_NEW event so the
 * single-writer BLP books it and updates the position. Returns 200 with the echoed trade —
 * booking is asynchronous, matching 009's POST /trade/ contract.
 *
 * <p>YU05 (FR-PTC42/FR-IMRG30): the Authorization header is threaded to the entitlement gate so
 * the market-trade command path is authn-gated on the same footing as order submission.
 */
@RestController
@RequestMapping("/")
public class MarketTradeController {
    private final OrderMatcherService orderMatcherService;

    public MarketTradeController(OrderMatcherService orderMatcherService) {
        this.orderMatcherService = orderMatcherService;
    }

    @PostMapping("/trades")
    public ResponseEntity<MarketTradeRequest> bookMarketTrade(
        @RequestBody MarketTradeRequest request,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        return ResponseEntity.ok(orderMatcherService.bookMarketTrade(request, authorization));
    }
}
