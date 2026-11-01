package finos.traderx.tradeprocessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finos.traderx.tradeprocessor.service.TradeSnapshotStore;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;

/**
 * FR-DRS-36, the operator's attestation of an unbound cursor (codeX review 2026-10-30 §16). The attestation is the
 * statement the STREAM_UNBOUND refusal prints, run as printed against a real projection_cursor: it binds the row to the
 * creation time of the stream that was reconciled, only while the row holds that cursor unbound. A stream recreated
 * after the attestation (codeX: reconciled A, recreated as B with new1..new8, only new6..new8 delivered) is refused, and
 * replaying the statement changes no row. Control: an attestation of the stream that is still there resumes it.
 */
@Tag("integration")
@Timeout(180)
class AttestationIT {
  static final Pattern STATEMENT = Pattern.compile("UPDATE projection_cursor SET stream_created_ns=\\d+ WHERE [^;]+;");

  static String attestation(Connection nc, JdbcTemplate jdbc, String stream) throws Exception {
    try {
      JetStreamFeed.requireRetained(nc, stream, "trade store", "s", 0, 5, TradeSnapshotStore.boundStream(jdbc, "s", 0));
    } catch (JetStreamFeed.Unbound refused) {
      Matcher m = STATEMENT.matcher(refused.getMessage());
      assertThat(m.find()).as(refused.getMessage()).isTrue();
      return m.group();
    }
    throw new AssertionError("an unbound cursor past 0 was accepted");
  }

  static List<String> feedFrom6(Connection nc, String stream) throws Exception {
    List<String> got = new CopyOnWriteArrayList<>();
    Thread feed = JetStreamFeed.startPipelined("t", nc, stream, StreamRecreateIT.SUBJECT,
        JetStreamFeed.fromSequence(StreamRecreateIT.SUBJECT, 6, 10), 10, Duration.ofMillis(100),
        msgs -> msgs.forEach(m -> got.add(new String(m.getData(), StandardCharsets.UTF_8))), head -> { });
    StreamRecreateIT.await(() -> got.size() >= 3, Duration.ofSeconds(5));
    feed.interrupt();
    feed.join(2000);
    return got;
  }

  @Test
  void anAttestationNamesTheStreamItReconciledAndIsGoodOnce() throws Exception {
    try (MariaDBContainer<?> db = new MariaDBContainer<>(
             "mariadb@sha256:f2f5e00999bd335343e09091e27e39c9386eb1822efcd0e0bac40b0003fa47e3");
         GenericContainer<?> c = StreamRecreateIT.nats("nats:2.14-alpine")) {
      db.start();
      JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
      jdbc.execute("CREATE TABLE projection_cursor (projection_scope VARCHAR(64) NOT NULL, partition_id INTEGER NOT NULL,"
          + " stream_seq BIGINT NOT NULL DEFAULT 0, consensus_seq BIGINT NOT NULL DEFAULT 0, snapshot_at DATETIME,"
          + " stream_created_ns BIGINT, PRIMARY KEY (projection_scope, partition_id))");
      try (Connection nc = Nats.connect("nats://" + c.getHost() + ":" + c.getMappedPort(4222))) {
        var jsm = nc.jetStreamManagement();

        // Control: cursor 5 saved before bindings existed, on a stream that is still there. Attested as printed, it
        // resumes at old6.
        jsm.addStream(StreamRecreateIT.config("KEPT"));
        StreamRecreateIT.publish(nc, "old", 8);
        jdbc.update("INSERT INTO projection_cursor(projection_scope,partition_id,stream_seq) VALUES ('s',0,5)");
        String kept = attestation(nc, jdbc, "KEPT");
        assertThat(jdbc.update(kept)).isEqualTo(1);
        long bound = JetStreamFeed.requireRetained(nc, "KEPT", "trade store", "s", 0, 5, TradeSnapshotStore.boundStream(jdbc, "s", 0));
        assertThat(TradeSnapshotStore.boundStream(jdbc, "s", 0)).isEqualTo(bound);
        assertThat(feedFrom6(nc, "KEPT")).containsExactly("old6", "old7", "old8");
        assertThat(jdbc.update(kept)).as("replaying an attestation changes nothing").isZero();
        jsm.deleteStream("KEPT");

        // codeX §16: stream A reconciled and attested, then recreated as B and refilled before the feed starts.
        jdbc.update("UPDATE projection_cursor SET stream_seq=5, stream_created_ns=NULL");
        jsm.addStream(StreamRecreateIT.config("A"));
        StreamRecreateIT.publish(nc, "old", 5);
        String attestedA = attestation(nc, jdbc, "A");
        assertThat(jdbc.update(attestedA)).isEqualTo(1);
        jsm.deleteStream("A");
        jsm.addStream(StreamRecreateIT.config("A"));
        StreamRecreateIT.publish(nc, "new", 8);
        assertThatThrownBy(() -> JetStreamFeed.requireRetained(nc, "A", "trade store", "s", 0, 5,
            TradeSnapshotStore.boundStream(jdbc, "s", 0)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("STREAM_RESET")
            .hasMessageContaining("skip its first 5 messages");

        // Replaying the saved attestation grants nothing: not on the bound row, and not after the row is rebuilt.
        assertThat(jdbc.update(attestedA)).isZero();
        jdbc.update("UPDATE projection_cursor SET stream_seq=0, stream_created_ns=NULL");
        assertThat(jdbc.update(attestedA)).isZero();
        assertThat(TradeSnapshotStore.boundStream(jdbc, "s", 0)).isNull();
      }
    }
  }
}
