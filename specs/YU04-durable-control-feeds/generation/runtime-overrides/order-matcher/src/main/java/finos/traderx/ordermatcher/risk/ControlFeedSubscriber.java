package finos.traderx.ordermatcher.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.ordermatcher.risk.ControlFeedBootstrapState.Outcome;
import finos.traderx.ordermatcher.risk.ControlFeedBootstrapState.Snapshot;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real-I/O adapter around {@link ControlFeedBootstrapState} implementing ADR-019's 5-step
 * protocol against one JetStream stream + one HTTP snapshot endpoint. Intentionally thin — all the
 * gap/epoch/watermark correctness logic lives in {@link ControlFeedBootstrapState} (fully
 * unit-tested); this class is verified live against the isolated staging environment (tasks.md
 * T-56), the same split already used for {@code JetStreamControlFeedPublisher} on the
 * account-service/reference-data side (there is no embeddable JetStream broker to unit-test
 * against, mirroring why those services' NATS adapters aren't unit-tested either).
 */
public final class ControlFeedSubscriber<T> {
    private static final Logger log = LoggerFactory.getLogger(ControlFeedSubscriber.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LIVE_FETCH_WAIT = Duration.ofSeconds(2);
    private static final int FETCH_BATCH = 256;

    private final String source;
    private final String streamName;
    private final String subject;
    private final String snapshotUrl;
    private final String natsAddress;
    private final int bufferCapacity;
    private final Function<JsonNode, T> snapshotRecordDecoder;
    private final Function<JsonNode, T> deltaPayloadDecoder;
    private final Function<T, String> canonicalLine;
    private final BiConsumer<T, Long> applier;
    private final Runnable onQuarantine;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

    private volatile ControlFeedBootstrapState<T> state;
    private volatile Connection connection;
    private volatile Thread liveThread;
    private volatile boolean stopped;

    public ControlFeedSubscriber(
            String source,
            String streamName,
            String subject,
            String snapshotUrl,
            String natsAddress,
            int bufferCapacity,
            Function<JsonNode, T> snapshotRecordDecoder,
            Function<JsonNode, T> deltaPayloadDecoder,
            Function<T, String> canonicalLine,
            BiConsumer<T, Long> applier,
            Runnable onQuarantine) {
        this.source = source;
        this.streamName = streamName;
        this.subject = subject;
        this.snapshotUrl = snapshotUrl;
        this.natsAddress = natsAddress;
        this.bufferCapacity = bufferCapacity;
        this.snapshotRecordDecoder = snapshotRecordDecoder;
        this.deltaPayloadDecoder = deltaPayloadDecoder;
        this.canonicalLine = canonicalLine;
        this.applier = applier;
        this.onQuarantine = onQuarantine;
        this.state = new ControlFeedBootstrapState<>(source, bufferCapacity);
    }

    public boolean isReady() {
        return state.isReady();
    }

    public long watermark() {
        return state.watermark();
    }

    public String source() {
        return source;
    }

    /**
     * Runs one full bootstrap attempt (ADR-019 steps 1-4) and, on success, starts live consumption
     * (step 5) on a background daemon thread. Throws on failure — the caller ({@link
     * ReplicaBootstrap}) retries with backoff, same discipline as YU03's one-shot fetch retry loop.
     */
    public synchronized void bootstrapOnce() throws Exception {
        this.state = new ControlFeedBootstrapState<>(source, bufferCapacity);

        Connection conn = connect();
        JetStreamManagement jsm = conn.jetStreamManagement();
        ensureStream(jsm);
        JetStream js = conn.jetStream();

        PullSubscribeOptions pullOptions = PullSubscribeOptions.builder()
            .configuration(ConsumerConfiguration.builder()
                .deliverPolicy(DeliverPolicy.New)
                .ackPolicy(AckPolicy.None)
                .filterSubject(subject)
                .build())
            .build();
        JetStreamSubscription subscription = js.subscribe(subject, pullOptions);

        // Step 2: fetch the watermarked snapshot. Anything published between the subscribe call
        // above and this fetch completing is exactly the handoff-window race ADR-019 closes —
        // it lands as a live message on `subscription` and is drained into the buffer next.
        Snapshot<T> snapshot = fetchSnapshot();

        // Step 1 (continued): drain whatever arrived during the snapshot fetch into the buffer.
        for (Message msg : subscription.fetch(FETCH_BATCH, Duration.ofMillis(200))) {
            bufferOne(msg);
        }

        Outcome outcome = state.installSnapshotAndReplay(
            snapshot,
            records -> ChecksumCodec.checksum(records, canonicalLine),
            applier,
            applier);

        if (outcome != Outcome.OK) {
            state.quarantine();
            onQuarantine.run();
            try {
                conn.close();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(source + " control feed bootstrap failed: " + outcome);
        }

        this.connection = conn;
        startLiveConsumption(subscription);
        log.info("{} control feed bootstrap complete: epoch={} watermark={}", source, state.epoch(), state.watermark());
    }

    private void bufferOne(Message msg) throws Exception {
        JsonNode node = mapper.readTree(msg.getData());
        long version = node.get("version").asLong();
        long epoch = node.get("epoch").asLong();
        T payload = deltaPayloadDecoder.apply(node);
        Outcome outcome = state.bufferDelta(version, epoch, payload);
        if (outcome == Outcome.BUFFER_OVERFLOW) {
            throw new IllegalStateException(source + " control feed pre-snapshot buffer overflow");
        }
    }

    private void startLiveConsumption(JetStreamSubscription subscription) {
        Thread thread = new Thread(() -> liveLoop(subscription), source + "-control-feed-live");
        thread.setDaemon(true);
        thread.start();
        this.liveThread = thread;
    }

    private void liveLoop(JetStreamSubscription subscription) {
        while (!stopped && state.isReady()) {
            try {
                List<Message> messages = subscription.fetch(FETCH_BATCH, LIVE_FETCH_WAIT);
                for (Message msg : messages) {
                    JsonNode node = mapper.readTree(msg.getData());
                    long version = node.get("version").asLong();
                    long epoch = node.get("epoch").asLong();
                    T payload = deltaPayloadDecoder.apply(node);
                    Outcome outcome = state.applyLiveDelta(version, epoch, payload, applier);
                    if (outcome == Outcome.GAP || outcome == Outcome.EPOCH_MISMATCH) {
                        log.warn("{} control feed {} at version={} epoch={} — quarantining, forcing re-bootstrap",
                            source, outcome, version, epoch);
                        state.quarantine();
                        onQuarantine.run();
                        return; // ReplicaBootstrap's monitor loop observes !isReady() and re-bootstraps
                    }
                }
            } catch (Exception ex) {
                if (stopped) {
                    return; // connection closed by stop(); exit quietly rather than logging noise
                }
                log.warn("{} control feed live consumption error (will retry): {}", source, ex.toString());
            }
        }
    }

    private Snapshot<T> fetchSnapshot() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(snapshotUrl))
            .timeout(HTTP_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(source + " snapshot fetch " + snapshotUrl + " -> HTTP " + response.statusCode());
        }
        JsonNode body = mapper.readTree(response.body());
        long epoch = body.get("sourceEpoch").asLong();
        long watermark = body.get("watermark").asLong();
        int count = body.get("count").asInt();
        String checksum = body.get("checksum").asText();
        List<T> records = new ArrayList<>();
        for (JsonNode recordNode : body.get("records")) {
            records.add(snapshotRecordDecoder.apply(recordNode));
        }
        return new Snapshot<>(epoch, watermark, count, checksum, records);
    }

    private Connection connect() throws Exception {
        return Nats.connect(new Options.Builder()
            .server(natsAddress)
            .connectionTimeout(HTTP_TIMEOUT)
            .maxReconnects(-1)
            .build());
    }

    private void ensureStream(JetStreamManagement jsm) throws Exception {
        try {
            StreamInfo existing = jsm.getStreamInfo(streamName);
            log.info("{} control feed stream already exists: subjects={}", source, existing.getConfiguration().getSubjects());
            return;
        } catch (JetStreamApiException ex) {
            if (ex.getApiErrorCode() != 10059) {
                throw ex; // 10059 = stream not found
            }
        }
        StreamConfiguration sc = StreamConfiguration.builder()
            .name(streamName)
            .subjects(subject)
            .storageType(StorageType.File)
            .build();
        jsm.addStream(sc);
        log.info("Created {} control feed stream: {}", source, streamName);
    }

    public void stop() {
        stopped = true;
        Connection conn = connection;
        if (conn != null) {
            try {
                conn.close();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
