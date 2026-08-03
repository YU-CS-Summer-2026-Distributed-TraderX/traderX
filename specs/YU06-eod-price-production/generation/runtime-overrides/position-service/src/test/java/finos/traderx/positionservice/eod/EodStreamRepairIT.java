package finos.traderx.positionservice.eod;

import static org.assertj.core.api.Assertions.assertThat;

import finos.traderx.positionservice.repository.PositionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The EOD stream contract, against a REAL JetStream broker.
 *
 * <p><b>Why this cannot be a unit test.</b> The property under test is that an ALREADY-EXISTING
 * stream which is missing a required subject gets repaired rather than accepted. Against a mocked
 * {@code JetStreamManagement} that assertion is vacuous: the mock returns whichever subject list
 * the test itself stubbed, so it can never contradict the caller, and the test would pass just as
 * happily against the broken version. Only a real broker holds an opinion of its own about what
 * the stream currently is.
 *
 * <p><b>What it protects.</b> {@code ensureStream} used to return the moment {@code getStreamInfo}
 * succeeded — which proves the stream NAME is taken, not that it carries the subjects this
 * consumer publishes to. A {@code TRADERX_EOD} left behind by an earlier incomplete create, or
 * created by a component that needed only one subject, was never repaired; the publish to the
 * missing subject then fails with "no responder" and the overnight batch chain breaks silently.
 * These tests fail against that version and pass against the repaired one.
 */
@Tag("integration")
@Testcontainers
class EodStreamRepairIT {

    private static final String STREAM = "TRADERX_EOD";
    private static final String PRICES_READY = "eod.prices.ready";
    private static final String PNL_DONE = "eod.pnl.done";

    /** `-js` enables JetStream; without it addStream fails and every case here errors identically. */
    @Container
    private final GenericContainer<?> nats =
        new GenericContainer<>(DockerImageName.parse("nats:2.10-alpine"))
            .withCommand("-js")
            .withExposedPorts(4222)
            .waitingFor(Wait.forListeningPort());

    private Connection connection;
    private EodPnlConsumer consumer;

    @BeforeEach
    void connect() throws Exception {
        String url = "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222);
        connection = Nats.connect(url);
        consumer = new EodPnlConsumer(
            new PositionRepository(null), null, null, new SimpleMeterRegistry(),
            url, true, STREAM, PRICES_READY, PNL_DONE, "eod-pnl");
    }

    @AfterEach
    void disconnect() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    private List<String> subjectsOf(String stream) throws Exception {
        return connection.jetStreamManagement().getStreamInfo(stream).getConfiguration().getSubjects();
    }

    @Test
    void createsTheStreamWithBothSubjectsWhenNoneExists() throws Exception {
        consumer.ensureStream(connection);

        assertThat(subjectsOf(STREAM)).containsExactlyInAnyOrder(PRICES_READY, PNL_DONE);
    }

    /**
     * The regression this class exists for. The stream is pre-created carrying only the INPUT
     * subject — exactly the state an earlier partial create leaves — and the consumer must add the
     * completion subject rather than accept the stream because the name resolved.
     */
    @Test
    void repairsAnExistingStreamThatIsMissingTheCompletionSubject() throws Exception {
        JetStreamManagement jsm = connection.jetStreamManagement();
        jsm.addStream(StreamConfiguration.builder()
            .name(STREAM)
            .subjects(PRICES_READY)
            .storageType(StorageType.File)
            .build());
        assertThat(subjectsOf(STREAM)).containsExactly(PRICES_READY);

        consumer.ensureStream(connection);

        assertThat(subjectsOf(STREAM)).containsExactlyInAnyOrder(PRICES_READY, PNL_DONE);
    }

    /** The mirror case: a stream carrying only the completion subject regains the input subject. */
    @Test
    void repairsAnExistingStreamThatIsMissingTheInputSubject() throws Exception {
        connection.jetStreamManagement().addStream(StreamConfiguration.builder()
            .name(STREAM)
            .subjects(PNL_DONE)
            .storageType(StorageType.File)
            .build());

        consumer.ensureStream(connection);

        assertThat(subjectsOf(STREAM)).containsExactlyInAnyOrder(PRICES_READY, PNL_DONE);
    }

    /**
     * A repair must not cost the stream anything else it was carrying. An unrelated subject added
     * by another component stays, because the fix MERGES rather than replacing the subject list —
     * a replace would silently unsubscribe whoever owned that subject.
     */
    @Test
    void aRepairPreservesSubjectsThisConsumerDoesNotOwn() throws Exception {
        connection.jetStreamManagement().addStream(StreamConfiguration.builder()
            .name(STREAM)
            .subjects(PRICES_READY, "eod.someone.else")
            .storageType(StorageType.File)
            .build());

        consumer.ensureStream(connection);

        assertThat(subjectsOf(STREAM))
            .containsExactlyInAnyOrder(PRICES_READY, PNL_DONE, "eod.someone.else");
    }

    /** Idempotent: a correct stream is left exactly as it was, and calling twice changes nothing. */
    @Test
    void aStreamThatIsAlreadyCorrectIsLeftAlone() throws Exception {
        consumer.ensureStream(connection);
        List<String> afterFirst = subjectsOf(STREAM);

        consumer.ensureStream(connection);

        assertThat(subjectsOf(STREAM)).containsExactlyInAnyOrderElementsOf(afterFirst);
    }

    /**
     * The end the repair exists to serve: after it, a publish to the completion subject is actually
     * accepted by the stream. This is the step that fails with "no responder" on an unrepaired
     * stream, and it is the one the overnight chain depends on.
     */
    @Test
    void theCompletionSubjectIsPublishableAfterARepair() throws Exception {
        connection.jetStreamManagement().addStream(StreamConfiguration.builder()
            .name(STREAM)
            .subjects(PRICES_READY)
            .storageType(StorageType.File)
            .build());

        consumer.ensureStream(connection);

        io.nats.client.api.PublishAck ack = connection.jetStream().publish(
            PNL_DONE, "{\"accountsMarked\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(ack.getStream()).isEqualTo(STREAM);
        assertThat(ack.getSeqno()).isPositive();
        assertThat(connection.jetStreamManagement().getStreamInfo(STREAM).getStreamState().getMsgCount())
            .isEqualTo(1L);
    }

    /**
     * A broker that cannot answer must surface, not be mistaken for "the stream is fine". Uses a
     * closed connection rather than an unroutable address: connecting to a dead port throws inside
     * Nats.connect, which would fail the setup instead of the call under test and prove nothing
     * about ensureStream.
     */
    @Test
    void anUnusableConnectionSurfacesRatherThanPassingSilently() throws Exception {
        connection.close();

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
            () -> consumer.ensureStream(connection));
    }
}
