package finos.traderx.ordermatcher.lmax;

import finos.traderx.ordermatcher.model.OrderSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.lmax.disruptor.EventHandler;

import java.util.Map;

/**
 * Output-ring booking bridge. The BLP emits TradeBooked events instead of POSTing trades
 * inside the match loop (FR-09B15); this handler feeds those events into the existing
 * trade pipeline (trade-service -> NATS -> trade-processor -> positions), preserving the
 * 009 trade/position contract while keeping the call off the acknowledgement path
 * (NFR-09B01: ack ends at input durability, booking fan-out is downstream).
 *
 * Demo simplification (strangler P2 boundary): full booking/position fusion into the BLP
 * (FR-09B08/FR-09B22) lands when the trade pipeline itself is restructured; until then a
 * trade-service submit failure is counted (reject + tradeSubmitFailures, as in 009) and
 * surfaced via metrics rather than rolling back the in-memory fill.
 */
public final class TradeSubmitHandler implements EventHandler<OutputEvent> {
    private static final Logger log = LoggerFactory.getLogger(TradeSubmitHandler.class);
    private static final OrderSide[] SIDES = OrderSide.values();

    private final RestTemplate restTemplate;
    private final String tradeServiceUrl;
    private final SymbolTable symbols;
    private final InMemoryOrderReadModel readModel;

    public TradeSubmitHandler(RestTemplate restTemplate, String tradeServiceUrl, SymbolTable symbols,
                              InMemoryOrderReadModel readModel) {
        this.restTemplate = restTemplate;
        this.tradeServiceUrl = tradeServiceUrl;
        this.symbols = symbols;
        this.readModel = readModel;
    }

    @Override
    public void onEvent(OutputEvent e, long sequence, boolean endOfBatch) {
        if (e.kind != OutputEvent.KIND_TRADE_BOOKED) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                "security", symbols.tickerFor(e.securityId),
                "quantity", e.tradeQty,
                "accountId", e.accountId,
                "side", SIDES[e.side].name()
            );
            ResponseEntity<Map> response = restTemplate.postForEntity(tradeServiceUrl, payload, Map.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                recordFailure(e, null);
            }
        } catch (Exception ex) {
            recordFailure(e, ex);
        }
    }

    private void recordFailure(OutputEvent e, Exception ex) {
        readModel.tradeSubmitFailures().increment();
        readModel.increment("reject");
        log.warn("Trade submit failed for order {} (qty {})", OrderSnapshot.orderIdFor(e.orderRef),
            e.tradeQty, ex);
    }
}
