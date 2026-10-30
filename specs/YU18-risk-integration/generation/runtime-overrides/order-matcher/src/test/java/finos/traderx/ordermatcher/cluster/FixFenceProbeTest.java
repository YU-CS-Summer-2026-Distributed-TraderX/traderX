package finos.traderx.ordermatcher.cluster;

import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.Message;
import io.nats.client.api.PublishAck;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR-099 (FR-GFS-18): the fence probe turns group commit on only for a server that checks the NAMED subject.
 * codeX review 2026-10-30 §4: the first probe also passed a server that ignores the cross-subject header whenever
 * the bucket already held messages (r > 1), and on any API error.
 */
class FixFenceProbeTest {
    private static final String PROBE = "_fence-probe-gw";

    /** A JetStream stream in miniature: one sequence, each subject's last; {@code named} = honours the header. */
    private static boolean probe(final long r, final boolean named, final int staleError) throws Exception {
        final Map<String, Long> last = new HashMap<>();
        final long[] seq = { r };
        final KeyValue kv = mock(KeyValue.class);
        when(kv.put(anyString(), any(byte[].class))).thenAnswer(i -> {
            last.put("$KV." + FixSessionStore.BUCKET + "." + i.getArgument(0), r);
            return r;
        });
        final JetStream js = mock(JetStream.class);
        when(js.publish(any(Message.class))).thenAnswer(i -> {
            final Message m = i.getArgument(0);
            final long expected = Long.parseLong(m.getHeaders().getFirst("Nats-Expected-Last-Subject-Sequence"));
            final String other = m.getHeaders().getFirst("Nats-Expected-Last-Subject-Sequence-Subject");
            final String checked = named && other != null ? other : m.getSubject();
            if (last.getOrDefault(checked, 0L) != expected) {
                final JetStreamApiException e = mock(JetStreamApiException.class);
                when(e.getApiErrorCode()).thenReturn(checked.equals(other) ? staleError : FixSessionStore.WRONG_LAST_SEQUENCE);
                throw e;
            }
            last.put(m.getSubject(), ++seq[0]);
            return mock(PublishAck.class);
        });
        return FixSessionStore.fenceHonoured(kv, js, PROBE);
    }

    @Test
    void aServerThatChecksTheNamedSubjectIsTrusted() throws Exception {
        assertTrue(probe(1, true, FixSessionStore.WRONG_LAST_SEQUENCE), "fresh bucket");
        assertTrue(probe(8, true, FixSessionStore.WRONG_LAST_SEQUENCE), "bucket already holding messages");
    }

    @Test
    void aServerThatIgnoresTheHeaderIsNotTrustedOnAFreshBucket() throws Exception {
        assertFalse(probe(1, false, FixSessionStore.WRONG_LAST_SEQUENCE));
    }

    /** The review's case: the first probe returned true here. */
    @Test
    void aServerThatIgnoresTheHeaderIsNotTrustedOnABusyBucket() throws Exception {
        assertFalse(probe(8, false, FixSessionStore.WRONG_LAST_SEQUENCE));
    }

    @Test
    void aStaleWriteRefusedForAnotherReasonProvesNothing() throws Exception {
        assertFalse(probe(8, true, 10077), "e.g. maximum bytes exceeded");
    }
}
