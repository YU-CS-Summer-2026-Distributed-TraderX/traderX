package finos.traderx.tradeprocessor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import finos.traderx.tradeprocessor.TradeFeedHandler;
import finos.traderx.tradeprocessor.repository.TradeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-DRS-61 (GKE run 2, 2026-11-12, nats-0's writes throttled): the recon reported 23,936 sides MISSING in the throttle
 * stage and 11,456 more in the stage that killed the leader, while the read model and the history store held every
 * side. In both, a lane caught up against a server still storing what had been sent before: the leader's lanes 0 and
 * 5 reconnected ("no receipt for 5 s with 1024 outstanding", a 20 s catch-up), and then a new leader republished them.
 * What was in flight from before lands behind what is sent after, so the partition's stream is out of tradeSeq order
 * for a while. The recon called a side MISSING the moment its partition's head passed it.
 *
 * <p>The shape here is the leader change, mid-read: the old leader's sides 1..640 are stored when the new leader reads
 * its cut; of the old leader's in-flight messages, 641..960 never land and 961..1280 land late; the new leader
 * republishes from 641. A sweep runs between the old leader's late messages and the new leader's.
 */
@Tag("integration")
@Testcontainers
class ReconLateArrivalIT {
  @Container static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.10-alpine").withCommand("-js")
      .withExposedPorts(4222);
  private static final int PARTITIONS = 8, SIDES = 2_000, BATCH = 64, ACCOUNT = 9_900_000;   // partition 0
  private static final long T0 = System.currentTimeMillis();

  @Test void aSideStoredBehindALaterOneAfterALeaderChangeIsMatchedNotMissing() throws Exception {
    try (var rig = new Rig(0)) {
      rig.publish(1, 640);       // stored before the new leader read the stream's last message
      rig.publish(961, 1280);    // the old leader's in-flight messages that land after it
      rig.recon.sweep();
      assertThat(rig.recon.status().missingInProjection()).as("sides the stream has not stored yet, behind its head").isZero();
      assertThat(rig.recon.suspects()).isEqualTo(320);
      rig.publish(641, SIDES);   // the new leader, from its cut
      rig.recon.sweep();
      var s = rig.recon.status();
      assertThat(s.missingInProjection() + s.fieldMismatch()).isZero();
      assertThat(s.matched()).isEqualTo(SIDES);
      assertThat(rig.recon.lateArrivals()).as("sides the stream stored after a later side of their partition").isEqualTo(320);
      assertThat(rig.recon.suspects()).isZero();
    }
  }

  /** A side that never lands is still MISSING, once the projection grace has passed. */
  @Test void aSideThatNeverLandsIsMissingAfterTheGrace() throws Exception {
    try (var rig = new Rig(1_000)) {
      rig.publish(1, 640);
      rig.publish(961, 1280);
      rig.recon.sweep();
      rig.publish(641, 699);
      rig.publish(701, SIDES);   // 700 is never published
      rig.recon.sweep();
      assertThat(rig.recon.status().missingInProjection()).as("within the grace").isZero();
      Thread.sleep(1_200);
      rig.recon.sweep();
      var s = rig.recon.status();
      assertThat(s.missingInProjection()).isEqualTo(1);
      assertThat(s.matched()).isEqualTo(SIDES - 1);
      assertThat(rig.recon.lateArrivals()).isEqualTo(319);
    }
  }

  /** An engine of {@link #SIDES} sides, all in its window, and partition 0's stream, which the test fills in an order. */
  private static final class Rig implements AutoCloseable {
    final HttpServer http;
    final Connection nc;
    final ReconciliationService recon;

    Rig(long graceMs) throws Exception {
      http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      http.createContext("/recon/trades/blotter", ex -> {
        Map<String, String> q = new HashMap<>();
        for (String kv : ex.getRequestURI().getQuery().split("&")) { q.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1)); }
        long since = Long.parseLong(q.get("sinceSeq"));
        int limit = Integer.parseInt(q.getOrDefault("limit", "1000"));
        StringBuilder body = new StringBuilder("[");
        for (long seq = since + 1; seq <= SIDES && seq <= since + limit; seq++) {
          if (body.length() > 1) body.append(',');
          body.append("{\"id\":\"1-").append(seq).append("-B\",\"tradeSeq\":").append(seq).append(',').append(FIELDS)
              .append(",\"execTimeMillis\":").append(T0 + seq).append('}');
        }
        byte[] bytes = body.append(']').toString().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
      });
      http.start();
      nc = Nats.connect("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222));
      String[] streams = new String[PARTITIONS];
      for (int p = 0; p < PARTITIONS; p++) {
        streams[p] = "TRADERX_FEED_P" + p;
        if (nc.jetStreamManagement().getStreamNames().contains(streams[p])) { nc.jetStreamManagement().deleteStream(streams[p]); }
        nc.jetStreamManagement().addStream(StreamConfiguration.builder().name(streams[p]).subjects("/trades." + p)
            .storageType(StorageType.Memory).build());
      }
      TradeFeedHandler feed = mock(TradeFeedHandler.class);
      when(feed.ledgerMode()).thenReturn(true);
      when(feed.partitionCount()).thenReturn(PARTITIONS);
      when(feed.natsConnection()).thenReturn(nc);
      when(feed.streamName(anyInt())).thenAnswer(i -> streams[i.getArgument(0, Integer.class)]);
      Map<Integer, Long> committed = new HashMap<>();
      for (int p = 0; p < PARTITIONS; p++) { committed.put(p, Long.MAX_VALUE); }
      TradeSnapshotStore store = mock(TradeSnapshotStore.class);
      when(store.cursors(any())).thenReturn(committed);
      recon = new ReconciliationService(mock(TradeRepository.class), "http://localhost:" + http.getAddress().getPort(),
          "dev-recon-control", new SimpleMeterRegistry());
      recon.subscribers(List.of(feed));
      recon.snapshotStore(store);
      if (graceMs > 0) { recon.projectionGraceMs(graceMs); }
    }

    /** Sides {@code from..to} onto partition 0's stream, 64 to a message as the bridge batches them, and stored. */
    void publish(long from, long to) throws Exception {
      StringBuilder batch = new StringBuilder();
      int held = 0;
      for (long seq = from; seq <= to; seq++) {
        if (held++ > 0) batch.append('\n');
        batch.append("{\"topic\":\"/trades.0\",\"type\":\"TradeOrder\",\"payload\":{\"id\":\"1-").append(seq).append("-B\",")
            .append(FIELDS).append("}}");
        if (held == BATCH || seq == to) { nc.jetStream().publish("/trades.0", batch.toString().getBytes(StandardCharsets.UTF_8)); batch.setLength(0); held = 0; }
      }
    }

    @Override public void close() throws Exception { nc.close(); http.stop(0); }
  }

  private static final String FIELDS = "\"accountId\":" + ACCOUNT + ",\"security\":\"AAPL\",\"side\":\"Buy\",\"quantity\":100,\"price\":241.30";
}
