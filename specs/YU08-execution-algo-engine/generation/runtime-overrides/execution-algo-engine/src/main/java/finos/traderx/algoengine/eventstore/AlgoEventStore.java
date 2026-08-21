package finos.traderx.algoengine.eventstore;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
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
import io.nats.client.api.StreamState;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JetStream-backed append log for {@link AlgoEvent}s (ADR-030). Reuses the same
 * {@code io.nats:jnats} client and stream-bootstrap idiom as YU04's
 * {@code JetStreamControlFeedPublisher}/{@code ControlFeedSubscriber}. A fresh <b>ephemeral</b>
 * pull consumer is created on every boot with {@code DeliverPolicy.All}, so every restart replays
 * the entire event log from the start and rebuilds every parent order (FR-AE08) — not just the
 * order in flight when a crash happened. Applying every event is a deterministic, idempotent
 * function of current state ({@link AlgoOrderState#apply}), so replaying the full history on every
 * boot is correct and requires no ack bookkeeping: a durable named consumer with explicit acks was
 * tried first and rejected (see ADR-030) because acking permanently advances a durable consumer's
 * position, so a later restart would only replay whatever was left unacked — silently forgetting
 * every parent order that had already fully completed before the restart.
 */
@Component
public class AlgoEventStore {
  private static final Logger log = LoggerFactory.getLogger(AlgoEventStore.class);
  private static final String STREAM_NAME = "TRADERX_ALGO_ENGINE";
  private static final String SUBJECT = "algo.events.>";
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
  /** Null while the consumer is being (re)built — the live loop idles and {@link #healthy()}
   * reports down for exactly that window. */
  private volatile JetStreamSubscription subscription;
  private volatile Consumer<AlgoEvent> applier;
  /** Events this PROCESS has applied off the stream, across every replay and the live loop. It is
   * the only thing this consumer knows that the broker cannot tell it, and it is what turns
   * "the stream is empty" into "the log I was rebuilt from is gone" on the repair path. */
  private final AtomicLong applied = new AtomicLong();

  public AlgoEventStore(@Value("${nats.address:nats://${NATS_BROKER_HOST:localhost}:4222}") String natsAddress) {
    this.natsAddress = natsAddress;
  }

  /** Blocks until the entire event log has been replayed and applied, then starts a background
   * thread that keeps applying new events as they are appended. */
  public synchronized void replayAndSubscribe(Consumer<AlgoEvent> applier) throws Exception {
    this.applier = applier;
    connection = Nats.connect(new Options.Builder()
        .server(natsAddress)
        .connectionTimeout(Duration.ofSeconds(10))
        .maxReconnects(-1)
        .connectionListener((conn, type) -> {
          if (type == ConnectionListener.Events.RECONNECTED) {
            rebuildIfConsumerGone();
          }
        })
        .build());
    rebuild();

    Thread thread = new Thread(this::liveLoop, "algo-engine-event-store-live");
    thread.setDaemon(true);
    thread.start();
    liveThread = thread;
  }

  /** Create the stream if needed, open a fresh ephemeral consumer, replay the log through the
   * applier, and hand the live loop the new subscription. Used at boot and on repair — the two
   * are the same operation, which is the whole point of replaying from the start every time. */
  private void rebuild() throws Exception {
    subscription = null; // the live loop idles rather than fetching on a dead consumer
    JetStreamManagement jsm = connection.jetStreamManagement();
    ensureStream(jsm);
    jetStream = connection.jetStream();

    PullSubscribeOptions pullOptions = PullSubscribeOptions.builder()
        .configuration(ConsumerConfiguration.builder()
            .deliverPolicy(DeliverPolicy.All)
            .ackPolicy(AckPolicy.None)
            .filterSubject(SUBJECT)
            .build())
        .build();
    JetStreamSubscription sub = jetStream.subscribe(SUBJECT, pullOptions);

    long appliedBefore = applied.get();
    int replayed = drain(sub, applier);
    subscription = sub;
    Recovery recovery = classifyRecovery(replayed, appliedBefore, jsm);
    if (recovery.alarming()) {
      log.warn("{}", recovery.message());
    } else {
      log.info("{}", recovery.message());
    }
  }

  /** What a replay of zero events actually meant. `replayed 0` on its own is correct against an
   * empty stream AND is exactly what permanent state loss looks like, and the accepted cost of
   * running this broker on non-durable storage is that the second one really happens — decided
   * 2026-08-21, recorded in issues/ as nats-jetstream-state-is-ephemeral-decide-deliberately.
   * These are facts about
   * different things — {@link #STREAM_EMPTY} and {@link #LOG_LOST} are about the broker's copy of
   * the log, {@link #CONSUMER_REPLAYED_NONE} is about this consumer's subscription, and
   * {@link #UNDETERMINED} is neither claim being available. */
  enum Verdict {
    /** The log was there and this consumer read it. The only quiet one. */
    REPLAYED,
    /** The stream holds nothing and has never held anything under this identity, so this consumer
     * missed nothing — and a first boot is indistinguishable from a wiped log FROM HERE. Named,
     * not resolved: the evidence that would separate them is not on this side. */
    STREAM_EMPTY,
    /** The stream holds nothing, but something this consumer can see says it once held messages —
     * either this process already applied some, or the stream's own last sequence is non-zero.
     * Definite loss, not an inference. */
    LOG_LOST,
    /** The stream holds messages and this consumer replayed none of them. The gap is on this
     * side — the subscription — not in the broker's storage. */
    CONSUMER_REPLAYED_NONE,
    /** The stream could not be inspected, so no claim above can be made. Deliberately not folded
     * into either of them: "could not determine" is a third answer, not a quiet version of one. */
    UNDETERMINED;
  }

  /** One verdict and the one line an operator reads, rendered together so the two cannot drift. */
  record Recovery(Verdict verdict, String message) {
    boolean alarming() {
      return verdict != Verdict.REPLAYED;
    }
  }

  /** Asks the broker what the stream holds and classifies the replay against it. An inspection
   * failure is carried through as {@link Verdict#UNDETERMINED} with the broker's own words rather
   * than being retried or guessed at. */
  private Recovery classifyRecovery(int replayed, long appliedBefore, JetStreamManagement jsm) {
    try {
      StreamState state = jsm.getStreamInfo(STREAM_NAME).getStreamState();
      return classifyRecovery(replayed, state.getMsgCount(), state.getLastSequence(),
          appliedBefore, null);
    } catch (Exception ex) {
      return classifyRecovery(replayed, -1, -1, appliedBefore, ex.toString());
    }
  }

  /**
   * Pure classifier, package-visible so each verdict can be exercised without a broker.
   *
   * @param replayed       events this replay applied
   * @param msgCount       messages the stream reports holding, or any negative value when
   *                       {@code inspectFailure} is set
   * @param lastSequence   the stream's last sequence; 0 means this incarnation of the stream has
   *                       never carried a message
   * @param appliedBefore  events this process had already applied off the stream before this replay
   * @param inspectFailure the broker's own description of why the stream could not be inspected,
   *                       or null when it was
   */
  static Recovery classifyRecovery(int replayed, long msgCount, long lastSequence,
      long appliedBefore, String inspectFailure) {
    if (inspectFailure != null) {
      return new Recovery(Verdict.UNDETERMINED, "replayed " + replayed + " algo-engine events from "
          + STREAM_NAME + ", but the broker could not be asked what the stream holds, so whether "
          + "this replay was complete is UNDETERMINED — treat neither an empty log nor a lost one "
          + "as ruled out: " + inspectFailure);
    }
    // Say UNRECOVERABLE, not gone. Measured on the kind rig 2026-08-21: after a broker wipe with a
    // TWAP parent in flight, this line printed AND the parent was still RUNNING — it submitted its
    // next bucket 72s later. Nothing resets the applier's state before a replay, so a wipe destroys
    // the broker's copy of the log while the process keeps its own in-memory schedule and keeps
    // working it. An earlier draft claimed the orders were "gone", which would send an operator
    // hunting for a parent that is still slicing. What actually died is the ability to rebuild them.
    if (msgCount == 0 && (appliedBefore > 0 || lastSequence > 0)) {
      String evidence = appliedBefore > 0
          ? "this process had already applied " + appliedBefore + " events off it"
          : "its last sequence is " + lastSequence + ", so it has carried messages";
      return new Recovery(Verdict.LOG_LOST, "STATE LOST: " + STREAM_NAME + " reports 0 messages and "
          + evidence + ". This engine keeps no store other than that log, so every parent order "
          + "those events carried is now UNRECOVERABLE: this process still holds them and keeps "
          + "running them, and nothing will rebuild them if it restarts.");
    }
    if (msgCount == 0) {
      return new Recovery(Verdict.STREAM_EMPTY, "replayed 0 algo-engine events: " + STREAM_NAME
          + " reports 0 messages and last sequence 0, so this consumer missed nothing that this "
          + "incarnation of the stream ever carried. Whether no parent order was ever published or "
          + "the stream was destroyed and recreated is NOT KNOWABLE from this side — a first boot "
          + "and a wiped log look identical here. If parent orders were in flight, they are gone.");
    }
    if (replayed == 0) {
      return new Recovery(Verdict.CONSUMER_REPLAYED_NONE, "replayed 0 algo-engine events although "
          + STREAM_NAME + " reports " + msgCount + " messages (last sequence " + lastSequence
          + ") — the log is still on the broker and this consumer read none of it, so the gap is "
          + "in this consumer's subscription. Nothing this engine holds came from those messages "
          + "until it replays them.");
    }
    return new Recovery(Verdict.REPLAYED, "replayed " + replayed + " of " + msgCount
        + " algo-engine events from " + STREAM_NAME + " (last sequence " + lastSequence + ")");
  }

  /**
   * A NATS restart recreates JetStream state, so this consumer — ephemeral, and never re-created
   * by the client — simply ceases to exist. The client reconnects the CONNECTION and stops there:
   * {@code fetch} on the dead subscription returns empty forever, the live thread stays alive,
   * and NOTHING throws. Measured on kind 2026-08-19: after a NATS restart the broker reported 27
   * connected clients and ZERO streams and consumers, with every pod Running 1/1, RESTARTS 0.
   * The EOD chain reached the same state by the same road and sat dead for ten hours.
   *
   * <p>Replaying the whole log on repair is correct for the same reason it is correct on boot:
   * {@link AlgoOrderState#apply} is deterministic and idempotent (ADR-030), which is exactly why
   * this consumer is ephemeral rather than durable.
   */
  private void rebuildIfConsumerGone() {
    JetStreamSubscription sub = subscription;
    try {
      if (sub != null
          && connection.jetStreamManagement().getConsumerNames(STREAM_NAME)
              .contains(sub.getConsumerName())) {
        return;
      }
    } catch (Exception ex) {
      // Could not tell. Rebuilding is idempotent and a silently dead subscriber is not.
      log.warn("could not list consumers on {} after a NATS reconnect, rebuilding anyway: {}",
          STREAM_NAME, ex.toString());
    }
    subscription = null;
    log.warn("algo-engine event-store consumer is gone after a NATS reconnect (a broker restart "
        + "recreates JetStream state); rebuilding and replaying {}", STREAM_NAME);
    Thread t = new Thread(() -> {
      while (!stopped && !Thread.currentThread().isInterrupted()) {
        try {
          rebuild();
          return;
        } catch (Exception ex) {
          log.warn("algo-engine event-store rebuild failed, retrying in 5s: {}", ex.toString());
          try {
            Thread.sleep(5000);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      }
    }, "algo-engine-event-store-rebind");
    t.setDaemon(true);
    t.start();
  }

  /** True when the NATS connection is up, the live-apply thread is still running, AND a live
   * JetStream consumer is actually bound. Feeds the readiness health group: a wedged/dead
   * subscriber must unready the pod.
   *
   * <p>The subscription check is load-bearing, not belt-and-braces. Without it this returned true
   * for a consumer the broker had destroyed — connection CONNECTED, thread alive, fetching from
   * nothing — which is the shape that let a NATS restart kill the event-sourced engine while the
   * pod reported Ready. It goes down only for the seconds a rebuild takes, and stays down if the
   * rebuild never succeeds, which is the visible failure this defect never had. */
  public boolean healthy() {
    Connection conn = connection;
    Thread live = liveThread;
    return conn != null && conn.getStatus() == Connection.Status.CONNECTED
        && live != null && live.isAlive()
        && subscription != null;
  }

  private int drain(JetStreamSubscription subscription, Consumer<AlgoEvent> applier) throws Exception {
    int count = 0;
    while (true) {
      List<Message> messages = subscription.fetch(FETCH_BATCH, FETCH_WAIT);
      if (messages.isEmpty()) {
        return count;
      }
      for (Message msg : messages) {
        apply(msg, applier);
        count++;
      }
    }
  }

  /** Applies an already-decoded full event log in order. Package-visible for an offline recovery
   * contract test; production replay decodes JetStream messages and invokes the same applier. */
  static int replayEvents(List<AlgoEvent> events, Consumer<AlgoEvent> applier) {
    events.forEach(applier);
    return events.size();
  }

  private void liveLoop() {
    while (!stopped) {
      try {
        JetStreamSubscription sub = subscription;
        if (sub == null) { // a rebuild is in flight; it owns the replay
          Thread.sleep(FETCH_WAIT.toMillis());
          continue;
        }
        List<Message> messages = sub.fetch(FETCH_BATCH, FETCH_WAIT);
        for (Message msg : messages) {
          apply(msg, applier);
        }
      } catch (Exception ex) {
        if (stopped) {
          return;
        }
        log.warn("algo-engine event-store live loop error (will retry): {}", ex.toString());
      }
    }
  }

  private void apply(Message msg, Consumer<AlgoEvent> applier) throws Exception {
    AlgoEvent event = mapper.readValue(msg.getData(), AlgoEvent.class);
    applier.accept(event);
    applied.incrementAndGet();
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
