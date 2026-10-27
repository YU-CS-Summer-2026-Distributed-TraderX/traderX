package finos.traderx.messaging.nats;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.messaging.Envelope;
import finos.traderx.messaging.PubSubException;
import finos.traderx.messaging.Subscriber;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * YU18 copy of the 006 subscriber (this file now shadows that layer for YU18). Three seams were
 * added for the replayable JetStream feed ({@code JetStreamFeed}): a subclass can keep the
 * connection but skip the core subscription ({@link #autoSubscribe}), borrow the connection
 * ({@link #connection}) and decode a raw envelope with the same mapper and type filter the
 * dispatcher applies ({@link #decode}). The core path is what 006 does, unchanged.
 */
public abstract class NatsJSONSubscriber<T> implements Subscriber<T>, InitializingBean {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  final JavaType envelopeType;
  final Class<T> objectType;

  org.slf4j.Logger log = LoggerFactory.getLogger(this.getClass().getName());

  private boolean connected = false;
  private Connection connection;
  private Dispatcher dispatcher;
  private String serverAddress = "nats://localhost:4222";
  private String defaultTopic = "/default";
  private String clientId = "subscriber";
  private long connectedSinceEpochMillis = 0;

  public NatsJSONSubscriber(Class<T> typeClass) {
    JavaType type = OBJECT_MAPPER.getTypeFactory().constructParametricType(NatsEnvelope.class, typeClass);
    this.envelopeType = type;
    this.objectType = typeClass;
  }

  public void setServerAddress(String addr) {
    serverAddress = addr;
  }

  public void setDefaultTopic(String topic) {
    defaultTopic = topic;
  }

  public void setClientId(String value) {
    clientId = value;
  }

  public String getServerAddress() {
    return serverAddress;
  }

  public String getClientId() {
    return clientId;
  }

  public long getUptimeSeconds() {
    if (!isConnected() || connectedSinceEpochMillis <= 0) {
      return 0;
    }
    return Math.max(0, (System.currentTimeMillis() - connectedSinceEpochMillis) / 1000);
  }

  public String getConnectionStatus() {
    try {
      if (connection == null) {
        return connected ? "connected" : "disconnected";
      }
      String status = connection.getStatus().name().toLowerCase(Locale.ROOT);
      if (status.contains("reconnect")) {
        return "reconnecting";
      }
      if (status.contains("connect")) {
        return "connected";
      }
      if (status.contains("disconnect")) {
        return "disconnected";
      }
      return status;
    } catch (Exception ignored) {
      return connected ? "connected" : "disconnected";
    }
  }

  @Override
  public boolean isConnected() {
    return "connected".equals(getConnectionStatus());
  }

  @Override
  public void subscribe(String topic) throws PubSubException {
    if (!isConnected() || dispatcher == null) {
      throw new PubSubException("Cannot subscribe - NATS connection is not ready");
    }
    dispatcher.subscribe(topic);
    log.info("Subscribed to {}", topic);
  }

  @Override
  public void unsubscribe(String topic) throws PubSubException {
    if (dispatcher != null) {
      dispatcher.unsubscribe(topic);
    }
  }

  @Override
  public void disconnect() throws PubSubException {
    try {
      if (connection != null) {
        connection.close();
      }
      connected = false;
      connectedSinceEpochMillis = 0;
      connection = null;
      dispatcher = null;
    } catch (Exception x) {
      throw new PubSubException("Failed to close NATS connection", x);
    }
  }

  @Override
  public void connect() throws PubSubException {
    try {
      Options options = new Options.Builder()
          .server(serverAddress)
          .maxReconnects(-1)
          .connectionTimeout(Duration.ofSeconds(5))
          .build();
      connection = Nats.connect(options);
      dispatcher = connection.createDispatcher(msg -> {
        try {
          NatsEnvelope<T> envelope = OBJECT_MAPPER.readValue(msg.getData(), envelopeType);
          if (envelope.getPayload() == null) {
            log.debug("Ignoring message with empty payload on {}", msg.getSubject());
            return;
          }
          if (!objectType.getSimpleName().equals(envelope.getType())) {
            log.debug("Ignoring non-target message type {}", envelope.getType());
            return;
          }
          onMessage(envelope, envelope.getPayload());
        } catch (Exception x) {
          log.error("Threw exception while handling incoming message", x);
        }
      });
      connected = true;
      connectedSinceEpochMillis = System.currentTimeMillis();
      log.info("Connected to NATS at {}", serverAddress);
    } catch (Exception x) {
      connected = false;
      connectedSinceEpochMillis = 0;
      throw new PubSubException("Cannot connect to NATS at " + serverAddress, x);
    }
  }

  /** The raw-envelope decode the dispatcher applies, for a feed that fetches its own messages.
   *  Returns null for an empty payload or a foreign type; throws on malformed JSON. */
  protected T decode(byte[] data) throws java.io.IOException {
    NatsEnvelope<T> envelope = OBJECT_MAPPER.readValue(data, envelopeType);
    if (envelope.getPayload() == null || !objectType.getSimpleName().equals(envelope.getType())) {
      return null;
    }
    return envelope.getPayload();
  }

  /** The live connection, for a subclass that runs a JetStream pull consumer on it. */
  protected Connection connection() {
    return connection;
  }

  /** False when the subclass consumes from somewhere other than a core subscription on the
   *  default topic (the JetStream feed); the connection is still opened for health reporting. */
  protected boolean autoSubscribe() {
    return true;
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    connect();
    if (autoSubscribe()) {
      subscribe(defaultTopic);
    }
  }
}
