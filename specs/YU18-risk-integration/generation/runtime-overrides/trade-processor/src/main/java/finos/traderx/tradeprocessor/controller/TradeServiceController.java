package finos.traderx.tradeprocessor.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * YU18 shadow of the baseline {@code POST /tradeservice/order}: it booked one SQL trade row per call, and rows mode
 * was retired (trade-history-tiers FR-THT-01). Trades reach the store only from the cluster's capture stream.
 */
@RestController
@RequestMapping("/tradeservice")
public class TradeServiceController {
  @PostMapping("/order")
  public ResponseEntity<Map<String, String>> processOrder() {
    return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error",
        "direct booking was retired with rows mode (FR-THT-01); trades are booked from the cluster's capture stream"));
  }
}
