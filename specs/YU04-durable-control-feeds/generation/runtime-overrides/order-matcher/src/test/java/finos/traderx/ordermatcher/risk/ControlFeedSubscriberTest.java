package finos.traderx.ordermatcher.risk;

import com.fasterxml.jackson.databind.JsonNode;
import finos.traderx.ordermatcher.risk.ControlFeedBootstrapState.Snapshot;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Hermetic contract for the real JetStream/HTTP adapter around ADR-019's state machine. */
class ControlFeedSubscriberTest {
    private record Rec(String key) {}

    @Test
    void adr019FiveStepBootstrapBuffersSnapshotWindowThenConsumesLiveInOrder() throws Exception {
        Connection connection = mock(Connection.class);
        JetStreamManagement management = mock(JetStreamManagement.class);
        JetStream jetStream = mock(JetStream.class);
        JetStreamSubscription subscription = mock(JetStreamSubscription.class);
        StreamInfo streamInfo = mock(StreamInfo.class);
        when(streamInfo.getConfiguration()).thenReturn(
            StreamConfiguration.builder().name("CONTROL").subjects("control.delta").build());
        when(connection.jetStreamManagement()).thenReturn(management);
        when(management.getStreamInfo("CONTROL")).thenReturn(streamInfo);
        when(connection.jetStream()).thenReturn(jetStream);
        when(jetStream.subscribe(eq("control.delta"), any(PullSubscribeOptions.class)))
            .thenReturn(subscription);
        Message duringSnapshot = message(2, 1, "during-snapshot");
        when(subscription.fetch(anyInt(), any(Duration.class)))
            .thenReturn(List.of(duringSnapshot));

        List<String> applied = new ArrayList<>();
        AtomicInteger quarantines = new AtomicInteger();
        List<Rec> snapshotRecords = List.of(new Rec("snapshot"));
        Snapshot<Rec> snapshot = new Snapshot<>(1, 1, 1,
            ChecksumCodec.checksum(snapshotRecords, rec -> rec.key() + ";"), snapshotRecords);
        ControlFeedSubscriber<Rec> subscriber = subscriber(connection, snapshot, applied, quarantines);

        subscriber.bootstrapOnce();

        assertTrue(subscriber.isReady());
        assertEquals(2, subscriber.watermark());
        assertEquals(List.of("snapshot@1", "during-snapshot@2"), applied);
        verify(jetStream).subscribe(eq("control.delta"), any(PullSubscribeOptions.class));

        assertTrue(subscriber.consumeLiveBatch(List.of(message(3, 1, "live"))));
        assertEquals(3, subscriber.watermark());
        assertEquals("live@3", applied.get(2));
        assertEquals(0, quarantines.get());
    }

    @Test
    void frImrg34LiveGapQuarantinesOnlyThisSubscriberAndRevokesReadiness() throws Exception {
        Connection connection = mock(Connection.class);
        JetStreamManagement management = mock(JetStreamManagement.class);
        JetStream jetStream = mock(JetStream.class);
        JetStreamSubscription subscription = mock(JetStreamSubscription.class);
        StreamInfo streamInfo = mock(StreamInfo.class);
        when(streamInfo.getConfiguration()).thenReturn(
            StreamConfiguration.builder().name("CONTROL").subjects("control.delta").build());
        when(connection.jetStreamManagement()).thenReturn(management);
        when(management.getStreamInfo("CONTROL")).thenReturn(streamInfo);
        when(connection.jetStream()).thenReturn(jetStream);
        when(jetStream.subscribe(eq("control.delta"), any(PullSubscribeOptions.class)))
            .thenReturn(subscription);
        when(subscription.fetch(anyInt(), any(Duration.class))).thenReturn(List.of());

        List<String> applied = new ArrayList<>();
        AtomicInteger quarantines = new AtomicInteger();
        List<Rec> noRecords = List.of();
        Snapshot<Rec> empty = new Snapshot<>(1, 10, 0,
            ChecksumCodec.checksum(noRecords, rec -> rec.key() + ";"), noRecords);
        ControlFeedSubscriber<Rec> subscriber = subscriber(connection, empty, applied, quarantines);
        subscriber.bootstrapOnce();

        assertFalse(subscriber.consumeLiveBatch(List.of(message(12, 1, "skips-11"))));
        assertFalse(subscriber.isReady());
        assertTrue(applied.isEmpty());
        assertEquals(1, quarantines.get());
    }

    private static ControlFeedSubscriber<Rec> subscriber(
            Connection connection, Snapshot<Rec> snapshot, List<String> applied,
            AtomicInteger quarantines) {
        return new ControlFeedSubscriber<>("test", "CONTROL", "control.delta",
            "http://unused/snapshot", "nats://unused", 16,
            node -> new Rec(node.get("key").asText()),
            node -> new Rec(node.get("key").asText()),
            rec -> rec.key() + ";",
            (rec, version) -> applied.add(rec.key() + "@" + version),
            quarantines::incrementAndGet,
            () -> connection, () -> snapshot, false);
    }

    private static Message message(long version, long epoch, String key) {
        Message message = mock(Message.class);
        String json = "{\"version\":" + version + ",\"epoch\":" + epoch
            + ",\"key\":\"" + key + "\"}";
        when(message.getData()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
