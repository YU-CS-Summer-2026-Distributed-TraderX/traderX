package finos.traderx.tradeprocessor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * YU05 (post-trade-compliance, ADR-022, FR-PTC04/05): scheduled forward reconciliation sweep
 * against order-matcher's replay-safe trade blotter (see order-matcher's {@code ReconController}).
 * Read-only against order-matcher (never mutates journal/BLP state, FR-PTC07); classification
 * result is in-memory only in slice 1 (no persisted recon-result table yet).
 *
 * <p>Known slice-1 limitation (documented in spec.md): because the blotter is a bounded, forward
 * window, this sweep can prove {@code MATCHED} / {@code MISSING_IN_PROJECTION} / {@code
 * FIELD_MISMATCH}, but cannot prove {@code ORPHAN_IN_PROJECTION} (a DB row with no corresponding
 * fill anywhere in a potentially much longer journal history) — deferred to FR-PTC10.</p>
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final TradeRepository tradeRepository;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String controlToken;

    private volatile long cursor = 0L;
    private volatile Instant lastSweepAt;
    private final LongAdder matched = new LongAdder();
    private final LongAdder missingInProjection = new LongAdder();
    private final LongAdder fieldMismatch = new LongAdder();

    public ReconciliationService(TradeRepository tradeRepository,
                                 @Value("${order-matcher.base-url:http://order-matcher:18110}") String baseUrl,
                                 @Value("${recon.control.token:dev-recon-control}") String controlToken) {
        this.tradeRepository = tradeRepository;
        this.baseUrl = baseUrl;
        this.controlToken = controlToken;
    }

    @Scheduled(fixedDelayString = "${recon.poll.interval-ms:10000}")
    public void sweep() {
        List<BlotterEntry> page;
        try {
            page = fetchBlotterPage(cursor);
        } catch (Exception ex) {
            log.warn("Reconciliation sweep skipped this cycle (order-matcher unreachable): {}", ex.toString());
            return;
        }
        for (BlotterEntry entry : page) {
            classify(entry);
            if (entry.tradeSeq() > cursor) {
                cursor = entry.tradeSeq();
            }
        }
        lastSweepAt = Instant.now();
        if (!page.isEmpty()) {
            log.info("Reconciliation sweep processed {} blotter entries; cursor now {}", page.size(), cursor);
        }
    }

    private void classify(BlotterEntry entry) {
        Optional<Trade> local = tradeRepository.findById(entry.id());
        if (local.isEmpty()) {
            missingInProjection.increment();
            log.warn("recon MISSING_IN_PROJECTION id={} account={} security={}",
                entry.id(), entry.accountId(), entry.security());
            return;
        }
        Trade trade = local.get();
        boolean matches = trade.getAccountId() != null && trade.getAccountId().equals(entry.accountId())
            && entry.security().equals(trade.getSecurity())
            && trade.getSide() != null && trade.getSide().name().equals(entry.side())
            && trade.getQuantity() != null && trade.getQuantity().equals(entry.quantity())
            && trade.getPrice() != null && trade.getPrice().compareTo(entry.price()) == 0;
        if (matches) {
            matched.increment();
        } else {
            fieldMismatch.increment();
            log.warn("recon FIELD_MISMATCH id={}", entry.id());
        }
    }

    private List<BlotterEntry> fetchBlotterPage(long sinceSeq) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + "/recon/trades/blotter?sinceSeq=" + sinceSeq);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(HTTP_TIMEOUT)
            .header("X-Recon-Control-Token", controlToken)
            .header("X-Recon-Operator", "trade-processor-reconciliation-service")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("recon blotter fetch failed: HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), new TypeReference<List<BlotterEntry>>() { });
    }

    public StatusSnapshot status() {
        return new StatusSnapshot(cursor, matched.sum(), missingInProjection.sum(), fieldMismatch.sum(), lastSweepAt);
    }

    /** Mirrors order-matcher's {@code TradeBlotter.TradeRecord} JSON shape exactly. */
    public record BlotterEntry(String id, long tradeSeq, int accountId, String security, String side,
                                int quantity, BigDecimal price, long execTimeMillis) { }

    public record StatusSnapshot(long cursor, long matched, long missingInProjection, long fieldMismatch,
                                  Instant lastSweepAt) { }
}
