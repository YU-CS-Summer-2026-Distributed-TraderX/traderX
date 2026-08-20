package finos.traderx.positionservice.eod;

import finos.traderx.positionservice.model.Position;
import finos.traderx.positionservice.repository.PositionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
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
    private volatile Dispatcher dispatcher;
    private final AtomicLong subscribed = new AtomicLong(0);

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
        // The one number that diagnoses a dead EOD chain, exported so it does not have to be
        // fished out of `nats/jsz` by hand. Deliberately NOT wired into /actuator/health: this
        // service also answers live position queries, and unreadying it out of rotation because
        // an overnight batch consumer lost its durable trades a silent batch failure for a loud
        // outage of everything else. The re-bind below is the repair; this is the alert.
        Gauge.builder("traderx_eod_pnl_subscribed", subscribed, AtomicLong::get)
            .description("1 when the EOD durable consumer is bound to the stream, 0 when it is not")
            .register(registry);
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
                BigDecimal marketValue = p.closingPrice().multiply(BigDecimal.valueOf(h.getQuantity()));
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
        retryInBackground("eod-pnl-consumer-init", this::connectAndSubscribe);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Retry forever on a daemon thread. Used for the initial connect and for the re-bind below,
     * which must not run on the NATS callback thread that delivers the reconnect event. */
    private void retryInBackground(String name, ThrowingRunnable action) {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    action.run();
                    return;
                } catch (Exception ex) {
                    log.warn("{} failed, retrying in 5s: {}", name, ex.toString());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private void connectAndSubscribe() throws Exception {
        connection = Nats.connect(new Options.Builder()
            .server(natsAddress)
            .connectionTimeout(Duration.ofSeconds(10))
            .maxReconnects(-1)
            .connectionListener((conn, type) -> {
                if (type == ConnectionListener.Events.RECONNECTED) {
                    rebindIfDurableGone();
                }
            })
            .build());
        jetStream = connection.jetStream();
        dispatcher = connection.createDispatcher();
        subscribe();
    }

    private void subscribe() throws Exception {
        ensureStream(connection);
        PushSubscribeOptions options = PushSubscribeOptions.builder()
            .stream(streamName).durable(durable).build();
        jetStream.subscribe(pricesReadySubject, dispatcher, this::onMessage, false, options);
        subscribed.set(1);
        log.info("eod consumer subscribed subject={} durable={} stream={}", pricesReadySubject, durable, streamName);
    }

    /**
     * A NATS restart recreates JetStream state, so the durable this consumer is subscribed to
     * simply ceases to exist. The client reconnects the CONNECTION and stops there: it does NOT
     * re-create a push consumer destroyed server-side, and NOTHING throws — the pod stays
     * Running 1/1, RESTARTS 0, logging nothing, with {@code eod.prices.ready} landing nowhere.
     * That is how the EOD chain sat dead for ten hours on 2026-08-19 looking perfectly healthy.
     *
     * <p>A reconnect is the only notice we get, so it is the trigger. If the durable survived,
     * our subscription came back with the connection and there is nothing to do. Re-creating it
     * replays from the start of the stream, which is safe by this class's existing contract:
     * {@link #process} is idempotent (NFR-EOD05) and {@code eod.pnl.done} is published with a
     * per-(date, version) message id, so JetStream de-duplicates the re-emitted event too.
     */
    private void rebindIfDurableGone() {
        try {
            if (connection.jetStreamManagement().getConsumerNames(streamName).contains(durable)) {
                return;
            }
        } catch (Exception ex) {
            // Could not tell. Re-subscribing is idempotent and losing the chain is not, so retry.
            log.warn("could not list consumers on {} after a NATS reconnect, re-subscribing anyway: {}",
                streamName, ex.toString());
        }
        subscribed.set(0);
        log.warn("EOD durable '{}' is gone after a NATS reconnect (a broker restart recreates "
            + "JetStream state); re-subscribing", durable);
        retryInBackground("eod-pnl-consumer-rebind", this::subscribe);
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

    /**
     * Package-private so the container-backed test can drive it against a real broker, the same
     * visibility {@code publishPnlDone} above already carries and for the same reason. The repair
     * branch below is only meaningful against a real JetStream: a mocked JetStreamManagement
     * returns the subjects the caller sets up, so it can never disagree with the caller.
     */
    void ensureStream(Connection conn) throws Exception {
        JetStreamManagement jsm = conn.jetStreamManagement();
        try {
            StreamInfo existing = jsm.getStreamInfo(streamName);
            // An existing stream is not necessarily a CORRECT one. getStreamInfo succeeding proves
            // only that the NAME is taken -- a stream left behind by an earlier incomplete create,
            // or created by a component that needed just one of these subjects, can be missing the
            // other. The publish then fails with "no responder" and the overnight batch chain
            // breaks silently, which is precisely the damage this method exists to prevent. Repair
            // rather than tolerate, matching RiskExtractMain.ensureStream in the same chain.
            List<String> present = existing.getConfiguration().getSubjects();
            List<String> merged = new ArrayList<>(present);
            for (String required : List.of(pricesReadySubject, pnlDoneSubject)) {
                if (!merged.contains(required)) {
                    merged.add(required);
                }
            }
            if (merged.size() != present.size()) {
                jsm.updateStream(StreamConfiguration.builder(existing.getConfiguration())
                    .subjects(merged)
                    .build());
                log.warn("repaired EOD stream {}: subjects {} -> {}", streamName, present, merged);
            } else {
                log.info("EOD stream already exists: subjects={}", present);
            }
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
