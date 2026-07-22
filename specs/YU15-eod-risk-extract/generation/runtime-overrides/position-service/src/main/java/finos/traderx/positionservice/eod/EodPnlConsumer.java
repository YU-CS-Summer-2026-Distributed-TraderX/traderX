package finos.traderx.positionservice.eod;

import finos.traderx.positionservice.model.Position;
import finos.traderx.positionservice.repository.PositionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PublishOptions;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * YU06 (eod-price-production, ADR-027/028, FR-EOD30-33): the first overnight batch job. A durable
 * JetStream consumer of {@code EOD_PRICES_READY}; on the event it marks every account's positions
 * against the exact {@code (session_date, version)} snapshot the event named (never live prices —
 * FR-EOD31), then emits {@code eod.pnl.done} (the next chain link).
 *
 * <p>Consumer-side fail-safe (FR-EOD32): if any held security is missing or not cleanly priced in
 * the snapshot, that account is halted (alert metric + log, no P&L rows) rather than marked wrong.
 * The marking is idempotent (NFR-EOD05), so durable redelivery after a restart is safe.
 *
 * <p>{@link #process} contains all the marking logic and is exercised directly by tests;
 * {@code eod.consumer.enabled=false} keeps the NATS subscription from ever opening a socket.
 */
@Component
public class EodPnlConsumer {
    private static final Logger log = LoggerFactory.getLogger(EodPnlConsumer.class);

    public record PnlResult(int accountsMarked, int accountsHalted, long completedAtMillis) { }

    private final PositionRepository positions;
    private final EodPriceSnapshotReader snapshotReader;
    private final EodPnlRepository pnlRepository;

    private final String natsAddress;
    private final boolean enabled;
    private final String streamName;
    private final String pricesReadySubject;
    private final String pnlDoneSubject;
    private final String durable;

    private final Counter accountsMarked;
    private final Counter accountsHalted;
    private final AtomicLong lastCompletedMillis = new AtomicLong(0);

    private volatile Connection connection;
    private volatile JetStream jetStream;

    public EodPnlConsumer(PositionRepository positions, EodPriceSnapshotReader snapshotReader,
                          EodPnlRepository pnlRepository, MeterRegistry registry,
                          @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress,
                          @Value("${eod.consumer.enabled:true}") boolean enabled,
                          @Value("${eod.stream:TRADERX_EOD}") String streamName,
                          @Value("${eod.subject.prices-ready:eod.prices.ready}") String pricesReadySubject,
                          @Value("${eod.subject.pnl-done:eod.pnl.done}") String pnlDoneSubject,
                          @Value("${eod.consumer.durable:eod-pnl}") String durable) {
        this.positions = positions;
        this.snapshotReader = snapshotReader;
        this.pnlRepository = pnlRepository;
        this.natsAddress = natsAddress;
        this.enabled = enabled;
        this.streamName = streamName;
        this.pricesReadySubject = pricesReadySubject;
        this.pnlDoneSubject = pnlDoneSubject;
        this.durable = durable;
        this.accountsMarked = Counter.builder("traderx_eod_pnl_accounts_marked")
            .description("Accounts marked to EOD closing prices").register(registry);
        this.accountsHalted = Counter.builder("traderx_eod_pnl_halted")
            .description("Accounts halted (missing/flagged closing price) — fail-safe alert").register(registry);
        Gauge.builder("traderx_eod_pnl_last_completed_millis", lastCompletedMillis, AtomicLong::get)
            .description("Epoch millis the consumer last finished a session").register(registry);
    }

    /**
     * Mark every account's positions against the named snapshot version. Fail-safe: an account with
     * any missing/flagged holding is halted (no rows written for it). Idempotent on
     * {@code (date, version, account, security)}. Pure DB work — callable directly in tests.
     */
    public PnlResult process(LocalDate sessionDate, int version) {
        Map<String, EodSnapshotPrice> snapshot = snapshotReader.read(sessionDate, version);
        Map<Integer, List<Position>> byAccount = positions.findAll().stream()
            .collect(Collectors.groupingBy(Position::getAccountId));
        long now = System.currentTimeMillis();
        List<EodPnlRepository.Row> toWrite = new ArrayList<>();
        int marked = 0;
        int halted = 0;
        for (Map.Entry<Integer, List<Position>> entry : byAccount.entrySet()) {
            int accountId = entry.getKey();
            List<Position> holdings = entry.getValue();
            boolean allUsable = holdings.stream().allMatch(h -> {
                EodSnapshotPrice p = snapshot.get(h.getSecurity());
                return p != null && p.isUsable();
            });
            if (!allUsable) {
                halted++;
                accountsHalted.increment();
                log.warn("eod pnl HALT account={} date={} version={} reason=missing_or_flagged_closing_price",
                    accountId, sessionDate, version);
                continue;
            }
            for (Position h : holdings) {
                EodSnapshotPrice p = snapshot.get(h.getSecurity());
                // YU15: multiplier-aware. A listed option's closing price is a per-share premium
                // and the contract controls 100 shares, so quantity x premium is not a market
                // value — it is a hundredth of one. Options only started reaching this table once
                // the schema was widened and the feed began quoting them, so this path is newly
                // exercised. Equities keep multiplier 1 and are bit-identical to before.
                BigDecimal marketValue = p.closingPrice()
                    .multiply(BigDecimal.valueOf(h.getQuantity()))
                    .multiply(BigDecimal.valueOf(OccSymbols.contractMultiplier(h.getSecurity())));
                toWrite.add(new EodPnlRepository.Row(sessionDate, version, accountId, h.getSecurity(),
                    h.getQuantity(), p.closingPrice(), marketValue, now));
            }
            marked++;
        }
        pnlRepository.upsertAll(toWrite);
        if (marked > 0) {
            accountsMarked.increment(marked);
        }
        lastCompletedMillis.set(now);
        log.info("eod pnl marked accounts={} halted={} rows={} date={} version={}",
            marked, halted, toWrite.size(), sessionDate, version);
        return new PnlResult(marked, halted, now);
    }

    // ---- NATS wiring (skipped entirely when eod.consumer.enabled=false) ----

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("eod.consumer.enabled=false; EOD P&L consumer NATS subscription disabled");
            return;
        }
        Thread t = new Thread(this::connectWithRetry, "eod-pnl-consumer-init");
        t.setDaemon(true);
        t.start();
    }

    private void connectWithRetry() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                connectAndSubscribe();
                return;
            } catch (Exception ex) {
                log.warn("eod consumer connect/subscribe failed, retrying in 5s: {}", ex.toString());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void connectAndSubscribe() throws Exception {
        connection = Nats.connect(new Options.Builder()
            .server(natsAddress)
            .connectionTimeout(Duration.ofSeconds(10))
            .maxReconnects(-1)
            .build());
        ensureStream(connection);
        jetStream = connection.jetStream();
        Dispatcher dispatcher = connection.createDispatcher();
        PushSubscribeOptions options = PushSubscribeOptions.builder()
            .stream(streamName).durable(durable).build();
        jetStream.subscribe(pricesReadySubject, dispatcher, this::onMessage, false, options);
        log.info("eod consumer subscribed subject={} durable={} stream={}", pricesReadySubject, durable, streamName);
    }

    private void onMessage(Message msg) {
        try {
            JSONObject event = new JSONObject(new String(msg.getData(), StandardCharsets.UTF_8));
            LocalDate sessionDate = LocalDate.parse(event.getString("sessionDate"));
            int version = event.getInt("version");
            PnlResult result = process(sessionDate, version);
            publishPnlDone(sessionDate, version, result);
            msg.ack();
        } catch (Exception ex) {
            // Do NOT ack — JetStream redelivers. Idempotent processing makes redelivery safe.
            log.error("eod consumer failed to process EOD_PRICES_READY; will be redelivered", ex);
        }
    }

    void publishPnlDone(LocalDate sessionDate, int version, PnlResult result) throws Exception {
        String payload = new JSONObject()
            .put("sessionDate", sessionDate.toString())
            .put("version", version)
            .put("accountsMarked", result.accountsMarked())
            .put("accountsHalted", result.accountsHalted())
            .put("completedAtMillis", result.completedAtMillis())
            .toString();
        String msgId = "eod-pnl-done-" + sessionDate + "-v" + version;
        jetStream.publish(pnlDoneSubject, payload.getBytes(StandardCharsets.UTF_8),
            PublishOptions.builder().stream(streamName).messageId(msgId).build());
        log.info("published eod.pnl.done msgId={} payload={}", msgId, payload);
    }

    private void ensureStream(Connection conn) throws Exception {
        JetStreamManagement jsm = conn.jetStreamManagement();
        try {
            StreamInfo existing = jsm.getStreamInfo(streamName);
            log.info("EOD stream already exists: subjects={}", existing.getConfiguration().getSubjects());
            return;
        } catch (JetStreamApiException ex) {
            if (ex.getApiErrorCode() != 10059) {
                throw ex; // 10059 = stream not found
            }
        }
        StreamConfiguration sc = StreamConfiguration.builder()
            .name(streamName)
            .subjects(pricesReadySubject, pnlDoneSubject)
            .storageType(StorageType.File)
            .build();
        jsm.addStream(sc);
        log.info("created EOD stream: {} subjects=[{}, {}]", streamName, pricesReadySubject, pnlDoneSubject);
    }

    @PreDestroy
    public void close() {
        Connection conn = connection;
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
