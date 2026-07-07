package finos.traderx.accountservice.outbox;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Durable outbox publisher for {@code account-service} (ADR-021, FR-IMRG32/33): a file-backed
 * JetStream stream of versioned account existence/identity deltas, consumed by order-matcher's
 * {@code ControlFeedSubscriber}. Reuses the same {@code io.nats:jnats} client and connection
 * config pattern (env vars {@code NATS_ADDRESS}/{@code NATS_BROKER_HOST}) order-matcher's
 * replication stream already uses — an unrelated stream, same client library and conventions.
 *
 * <p>Connects lazily on the first {@link #publish} call rather than at construction time: this is
 * a control-plane path (250ms poll interval, NFR-IMRG01 does not apply — ADR-021), so there is no
 * reason to couple application startup to broker availability, and it means this bean never opens
 * a socket in a test context where {@code outbox.publisher.enabled=false} keeps {@link
 * AccountOutboxPublisher} from ever calling {@link #publish}.
 */
@Component
public class JetStreamControlFeedPublisher implements ControlFeedPublisher {
  private static final Logger log = LoggerFactory.getLogger(JetStreamControlFeedPublisher.class);

  static final String STREAM_NAME = "TRADERX_CONTROL_ACCOUNT";
  static final String SUBJECT = "traderx.control.account.deltas";

  private final String natsAddress;
  private volatile JetStream jetStream;
  private volatile Connection connection;

  public JetStreamControlFeedPublisher(
      @Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress) {
    this.natsAddress = natsAddress;
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

  static void ensureStream(Connection conn) throws Exception {
    JetStreamManagement jsm = conn.jetStreamManagement();
    try {
      StreamInfo existing = jsm.getStreamInfo(STREAM_NAME);
      log.info("Control feed stream already exists: subjects={}", existing.getConfiguration().getSubjects());
      return;
    } catch (JetStreamApiException ex) {
      if (ex.getApiErrorCode() != 10059) {
        throw ex; // 10059 = stream not found
      }
    }
    StreamConfiguration sc = StreamConfiguration.builder()
        .name(STREAM_NAME)
        .subjects(SUBJECT)
        .storageType(StorageType.File)
        .build();
    jsm.addStream(sc);
    log.info("Created control feed stream: {}", STREAM_NAME);
  }

  @Override
  public void publish(String natsMsgId, String payloadJson) throws Exception {
    PublishOptions options = PublishOptions.builder().messageId(natsMsgId).build();
    jetStream().publish(SUBJECT, payloadJson.getBytes(StandardCharsets.UTF_8), options);
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
