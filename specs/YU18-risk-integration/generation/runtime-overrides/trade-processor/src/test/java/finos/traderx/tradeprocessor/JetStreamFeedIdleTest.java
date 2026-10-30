package finos.traderx.tradeprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.ConsumerInfo;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * The idle advance moves a cursor-acknowledged view to the stream head when it is caught up. A message delivered to
 * this client after the empty fetch is not pending, only ack pending; advancing then made the next fetch ignore it as
 * a replay (measured: 1,408 of 3,000,000 sides lost at P=16, downstream-at-400k.md §7.12). Caught up = nothing pending
 * AND nothing delivered unapplied.
 */
class JetStreamFeedIdleTest {
  private static List<Long> idles(long pending, long ackPending, boolean pipelined) throws Exception {
    Connection nc = mock(Connection.class, RETURNS_DEEP_STUBS);
    JetStreamSubscription sub = mock(JetStreamSubscription.class);
    when(nc.jetStream().subscribe(anyString(), any(PullSubscribeOptions.class))).thenReturn(sub);
    when(nc.jetStreamManagement().getStreamInfo(anyString()).getStreamState().getLastSequence()).thenReturn(500L);
    when(sub.fetch(anyInt(), any(Duration.class))).thenAnswer(i -> { Thread.sleep(5); return List.of(); });
    ConsumerInfo info = mock(ConsumerInfo.class);
    when(info.getNumPending()).thenReturn(pending);
    when(info.getNumAckPending()).thenReturn(ackPending);
    when(sub.getConsumerInfo()).thenReturn(info);
    List<Long> idles = new CopyOnWriteArrayList<>();
    var consumer = JetStreamFeed.fromSequence("/trades.0", 1, 10);
    Thread t = pipelined
        ? JetStreamFeed.startPipelined("t", nc, "S", "/trades.0", consumer, 10, Duration.ofMillis(5), m -> { }, idles::add)
        : JetStreamFeed.start("t", nc, "S", "/trades.0", consumer, 10, Duration.ofMillis(5), m -> { }, idles::add);
    Thread.sleep(400);
    t.interrupt();
    t.join(2000);
    return idles;
  }

  @Test void aDeliveredButUnappliedMessageHoldsTheCursor() throws Exception {
    assertEquals(List.of(), idles(0, 3, false));
    assertEquals(List.of(), idles(0, 3, true));
    assertEquals(List.of(), idles(2, 0, true));
  }

  @Test void caughtUpMovesTheCursorToTheHead() throws Exception {
    assertEquals(List.of(500L), idles(0, 0, false));
    assertEquals(List.of(500L), idles(0, 0, true));
  }
}
