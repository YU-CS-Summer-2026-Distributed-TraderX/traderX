package finos.traderx.tradeprocessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nats.client.Connection;
import io.nats.client.Nats;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;

/**
 * FR-DRS-36, at a restart: a saved cursor is bound to the stream it was taken on (its creation time), and
 * requireRetained refuses a stream recreated under the same name, even one that has grown past the cursor, which
 * both sequence checks accept. codeX review 2026-10-30 §6 (ReviewStreamIdentityIT: cursor 5, recreated with 8 messages,
 * only 6..8 delivered). On NATS 2.10 and 2.14 (production).
 */
@Tag("integration")
@Timeout(120)
class StreamIdentityIT {
  @ParameterizedTest @ValueSource(strings = {"nats:2.10-alpine", "nats:2.14-alpine"})
  void aCursorIsRefusedOnARecreatedStreamAndKeptOnItsOwn(String image) throws Exception {
    try (GenericContainer<?> c = StreamRecreateIT.nats(image);
         Connection nc = Nats.connect("nats://" + c.getHost() + ":" + c.getMappedPort(4222))) {
      var jsm = nc.jetStreamManagement();
      jsm.addStream(StreamRecreateIT.config("IDENTITY"));
      StreamRecreateIT.publish(nc, "old", 5);
      long bound = JetStreamFeed.requireRetained(nc, "IDENTITY", "test", 0, 0, null);

      // Controls: the stream the cursor was taken on resumes, also after a limits update (ensureStream's path).
      assertThat(JetStreamFeed.requireRetained(nc, "IDENTITY", "test", 0, 5, bound)).isEqualTo(bound);
      JetStreamFeed.ensureStream(nc, "IDENTITY", 1L << 30, Duration.ofHours(1), StreamRecreateIT.SUBJECT);
      assertThat(JetStreamFeed.requireRetained(nc, "IDENTITY", "test", 0, 5, bound)).as("an update is the same stream")
          .isEqualTo(bound);

      jsm.deleteStream("IDENTITY");
      jsm.addStream(StreamRecreateIT.config("IDENTITY"));
      StreamRecreateIT.publish(nc, "new", 8);
      assertThatThrownBy(() -> JetStreamFeed.requireRetained(nc, "IDENTITY", "test", 0, 5, bound))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("STREAM_RESET")
          .hasMessageContaining("skip its first 5 messages");
      long now = JetStreamFeed.requireRetained(nc, "IDENTITY", "test", 0, 0, bound);
      assertThat(now).as("cursor 0 skips nothing: it resumes and rebinds").isNotEqualTo(bound);
      assertThat(JetStreamFeed.requireRetained(nc, "IDENTITY", "test", 0, 5, null))
          .as("a cursor recorded before the binding existed is accepted once and bound").isEqualTo(now);
    }
  }
}
