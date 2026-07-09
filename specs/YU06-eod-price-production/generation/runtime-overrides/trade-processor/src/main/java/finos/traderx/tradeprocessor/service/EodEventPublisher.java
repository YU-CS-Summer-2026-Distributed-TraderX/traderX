package finos.traderx.tradeprocessor.service;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PublishOptions;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * YU06 (eod-price-production, ADR-027, FR-EOD21/22): durable JetStream publisher of the
 * {@code EOD_PRICES_READY} gate event. Reuses the exact pattern YU04's
 * {@code JetStreamControlFeedPublisher} established — {@code io.nats:jnats}, a file-storage stream,
 * lazy connect, dedup via a deterministic message id — on an unrelated stream ({@code TRADERX_EOD}).
 *
 * <p>File storage + a message id per {@code (sessionDate, version)} give the two guarantees the
 * gate needs: a consumer that boots after publish still receives the event (durability, NFR-EOD02),
 * and a re-published event (idempotent {@code /publish} re-run) is de-duplicated by the broker.
 * Connects lazily so a test with {@code eod.event.enabled=false} never opens a socket.
 */
@Component
public class EodEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(EodEventPublisher.class);

    private final String natsAddress;
    private final String streamName;
    private final String subject;
    private final boolean enabled;
    private volatile JetStream jetStream;
    private volatile Connection connection;

    public EodEventPublisher(
        @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress,
        @Value("${eod.stream:TRADERX_EOD}") String streamName,
        @Value("${eod.subject.prices-ready:eod.prices.ready}") String subject,
        @Value("${eod.event.enabled:true}") boolean enabled) {
        this.natsAddress = natsAddress;
        this.streamName = streamName;
        this.subject = subject;
        this.enabled = enabled;
    }

    /** Publish {@code EOD_PRICES_READY} for a just-published version. No-op if disabled (tests). */
    public void publishPricesReady(LocalDate sessionDate, int version, int instrumentCount,
                                   long publishedAtMillis) throws Exception {
        if (!enabled) {
            log.info("eod.event.enabled=false; skipping EOD_PRICES_READY publish for {} v{}", sessionDate, version);
            return;
        }
        String payload = new JSONObject()
            .put("sessionDate", sessionDate.toString())
            .put("version", version)
            .put("instrumentCount", instrumentCount)
            .put("publishedAtMillis", publishedAtMillis)
            .toString();
        String msgId = "eod-prices-ready-" + sessionDate + "-v" + version;
        PublishOptions options = PublishOptions.builder().stream(streamName).messageId(msgId).build();
        jetStream().publish(subject, payload.getBytes(StandardCharsets.UTF_8), options);
        log.info("published EOD_PRICES_READY subject={} msgId={} payload={}", subject, msgId, payload);
    }

    private synchronized JetStream jetStream() throws Exception {
        if (jetStream == null) {
            connection = Nats.connect(new Options.Builder()
                .server(natsAddress)
                .connectionTimeout(Duration.ofSeconds(10))
                .maxReconnects(-1)
                .build());
            ensureStream(connection);
            jetStream = connection.jetStream();
        }
        return jetStream;
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
        // Cover both chain subjects on one stream so eod.pnl.done is durable too (position-service
        // publishes it; the stream just needs to exist and retain it).
        StreamConfiguration sc = StreamConfiguration.builder()
            .name(streamName)
            .subjects(subject, "eod.pnl.done")
            .storageType(StorageType.File)
            .build();
        jsm.addStream(sc);
        log.info("created EOD stream: {} subjects=[{}, eod.pnl.done]", streamName, subject);
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
