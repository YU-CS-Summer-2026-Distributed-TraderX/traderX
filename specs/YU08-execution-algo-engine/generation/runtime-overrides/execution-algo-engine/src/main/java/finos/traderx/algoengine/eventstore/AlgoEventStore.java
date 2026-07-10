package finos.traderx.algoengine.eventstore;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JetStream-backed append log for {@link AlgoEvent}s (ADR-030). Reuses the same
 * {@code io.nats:jnats} client and stream-bootstrap idiom as YU04's
 * {@code JetStreamControlFeedPublisher}/{@code ControlFeedSubscriber}. One durable pull consumer
 * both replays every event on boot (rebuilding {@link AlgoOrderState}) and continues receiving new
 * events live — an event is acked only once the caller's {@code applier} has applied it, so a crash
 * between append and ack simply redelivers and reapplies the same (idempotent) event.
 */
@Component
public class AlgoEventStore {
  private static final Logger log = LoggerFactory.getLogger(AlgoEventStore.class);
  private static final String STREAM_NAME = "TRADERX_ALGO_ENGINE";
  private static final String SUBJECT = "algo.events.>";
  private static final String DURABLE_CONSUMER = "algo-engine-state";
  private static final int FETCH_BATCH = 64;
  private static final Duration FETCH_WAIT = Duration.ofMillis(500);

  private final String natsAddress;
  private final ObjectMapper mapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private volatile Connection connection;
  private volatile JetStream jetStream;
  private volatile Thread liveThread;
  private volatile boolean stopped;

  public AlgoEventStore(@Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress) {
    this.natsAddress = natsAddress;
  }

  /** Blocks until every currently-unacked event has been replayed and applied, then starts a
   * background thread that keeps applying new events as they are appended. */
  public synchronized void replayAndSubscribe(Consumer<AlgoEvent> applier) throws Exception {
    connection = Nats.connect(new Options.Builder()
        .server(natsAddress)
        .connectionTimeout(Duration.ofSeconds(10))
        .maxReconnects(-1)
        .build());
    JetStreamManagement jsm = connection.jetStreamManagement();
    ensureStream(jsm);
    jetStream = connection.jetStream();

    PullSubscribeOptions pullOptions = PullSubscribeOptions.builder()
        .durable(DURABLE_CONSUMER)
        .configuration(ConsumerConfiguration.builder()
            .durable(DURABLE_CONSUMER)
            .deliverPolicy(DeliverPolicy.All)
            .ackPolicy(AckPolicy.Explicit)
            .filterSubject(SUBJECT)
            .build())
        .build();
    JetStreamSubscription subscription = jetStream.subscribe(SUBJECT, pullOptions);

    int replayed = drain(subscription, applier);
    log.info("replayed {} algo-engine events from {}", replayed, STREAM_NAME);

    Thread thread = new Thread(() -> liveLoop(subscription, applier), "algo-engine-event-store-live");
    thread.setDaemon(true);
    thread.start();
    liveThread = thread;
  }

  private int drain(JetStreamSubscription subscription, Consumer<AlgoEvent> applier) throws Exception {
    int count = 0;
    while (true) {
      List<Message> messages = subscription.fetch(FETCH_BATCH, FETCH_WAIT);
      if (messages.isEmpty()) {
        return count;
      }
      for (Message msg : messages) {
        applyAndAck(msg, applier);
        count++;
      }
    }
  }

  private void liveLoop(JetStreamSubscription subscription, Consumer<AlgoEvent> applier) {
    while (!stopped) {
      try {
        List<Message> messages = subscription.fetch(FETCH_BATCH, FETCH_WAIT);
        for (Message msg : messages) {
          applyAndAck(msg, applier);
        }
      } catch (Exception ex) {
        if (stopped) {
          return;
        }
        log.warn("algo-engine event-store live loop error (will retry): {}", ex.toString());
      }
    }
  }

  private void applyAndAck(Message msg, Consumer<AlgoEvent> applier) throws Exception {
    AlgoEvent event = mapper.readValue(msg.getData(), AlgoEvent.class);
    applier.accept(event);
    msg.ack();
  }

  public void append(AlgoEvent event) throws Exception {
    byte[] payload = mapper.writeValueAsBytes(event);
    jetStream.publish("algo.events." + event.getParentOrderId(), payload);
  }

  private void ensureStream(JetStreamManagement jsm) throws Exception {
    try {
      StreamInfo existing = jsm.getStreamInfo(STREAM_NAME);
      log.info("algo-engine event stream already exists: subjects={}", existing.getConfiguration().getSubjects());
      return;
    } catch (JetStreamApiException ex) {
      if (ex.getApiErrorCode() != 10059) { // 10059 = stream not found
        throw ex;
      }
    }
    StreamConfiguration sc = StreamConfiguration.builder()
        .name(STREAM_NAME)
        .subjects(SUBJECT)
        .storageType(StorageType.File)
        .build();
    jsm.addStream(sc);
    log.info("created algo-engine event stream: {}", STREAM_NAME);
  }

  @PreDestroy
  public void close() {
    stopped = true;
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
