package finos.traderx.historyfeed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * FR-THT-03 / SC-THT-03 on real infrastructure: a ClickHouse 25.8 holding three days of trades on its local disk is
 * put on the shipped {@code tiered} policy ({@code k8s/clickhouse-tiered.xml}) by {@code createTables(hotDays)}; the
 * two old date partitions move to the object disk (MinIO, the S3 protocol ClickHouse speaks to GCS), today's stays,
 * and every query answers with the same rows as before the move. Negative control: with the objects behind the cold
 * disk deleted, the same query no longer answers the same rows (it reads the object store, not a local copy).
 */
@Tag("integration")
@Testcontainers
@Timeout(300)
class HistoryTierIT {
  static final Network NET = Network.newNetwork();
  // S3Mock: an S3-protocol object store that takes any credentials (MinIO's images left Docker Hub in 2025).
  @Container static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:3.12.0")
      .withNetwork(NET).withNetworkAliases("s3").withEnv("initialBuckets", "history").withEnv("root", "/data");
  @Container static final GenericContainer<?> CH = new GenericContainer<>("clickhouse/clickhouse-server:25.8")
      .withNetwork(NET).dependsOn(S3)
      .withEnv("CLICKHOUSE_SKIP_USER_SETUP", "1")
      .withEnv("HISTORY_COLD_ENDPOINT", "http://s3:9090/history/ch/")
      .withEnv("HISTORY_COLD_KEY_ID", "tier").withEnv("HISTORY_COLD_SECRET", "tier-secret")
      .withCopyFileToContainer(MountableFile.forHostPath(Path.of("k8s/clickhouse-tiered.xml")),
          "/etc/clickhouse-server/config.d/tiered.xml")
      .withExposedPorts(8123);

  static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
  static final LocalDate[] DAYS = {TODAY.minusDays(5), TODAY.minusDays(3), TODAY};

  @Test void old_partitions_move_to_the_object_disk_and_answer_the_same_rows() throws Exception {
    ClickHouseStore ch = new ClickHouseStore("http://" + CH.getHost() + ":" + CH.getMappedPort(8123));
    HistoryFeedKillIT.waitFor(() -> { try { ch.exec("SELECT 1"); return true; } catch (Exception e) { return false; } }, 60_000);
    ch.createTables();                                   // the store as it runs today: everything local
    long seq = 0;
    for (LocalDate d : DAYS) {                           // one single-day insert per day (FR-HST-02)
      Rows b = new Rows(true, 10_000);
      long t0 = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000;
      for (int i = 0; i < 10_000; i++, seq++) {
        b.ts[i] = t0 + i * 1000L; b.seq[i] = seq + 1; b.key[i] = seq + 1; b.side[i] = (byte) (i % 2 == 0 ? 'B' : 'S');
        b.acct[i] = 100 + i % 7; b.sec[i] = i % 3 == 0 ? "IBM" : "MSFT"; b.qty[i] = 100 + i % 50;
        b.px[i] = 100 + (i % 400) / 100.0; b.ref[i] = seq + 1; b.cseq[i] = seq + 1;
        b.n++;
      }
      ch.insert(b, 0);
    }
    assertEquals("0", disks(ch, "cold_object"), "nothing is cold before the policy");
    Map<String, String> before = answers(ch);

    ch.createTables(1);                                  // FR-THT-03: on the tiered policy, older than 1 day -> cold
    HistoryFeedKillIT.waitFor(() -> disks(ch, "cold_object").equals("2"), 120_000);
    assertEquals(DAYS[0] + "," + DAYS[1], partitions(ch, "cold_object"));
    assertEquals(TODAY.toString(), partitions(ch, "default"));
    long mutations = Long.parseLong(ch.exec("SELECT count() FROM system.mutations").trim());
    ch.createTables(1);                                  // a restart on the policy changes nothing
    assertEquals(mutations, Long.parseLong(ch.exec("SELECT count() FROM system.mutations").trim()));

    assertEquals(before, answers(ch), "the same rows before and after the move");

    // Negative control: the cold rows come from the object store. Delete its objects and the answer must change.
    S3.execInContainer("sh", "-c", "rm -rf /data/history/*/");
    ch.exec("SYSTEM DROP MARK CACHE"); ch.exec("SYSTEM DROP UNCOMPRESSED CACHE"); ch.exec("SYSTEM DROP FILESYSTEM CACHE");
    Map<String, String> after;
    try { after = answers(ch); } catch (Exception e) { after = null; }   // an error is the expected outcome
    assertNotEquals(before, after, "the cold partitions answered without their objects");
  }

  /** Every read the history serves, over the moved days and today: counts, the whole ordered table, one fills page. */
  static Map<String, String> answers(ClickHouseStore ch) throws Exception {
    return Map.of(
        "count", ch.exec("SELECT toDate(ts) d, count() FROM trades GROUP BY d ORDER BY d"),
        "hash", ch.exec("SELECT cityHash64(groupArray((part, seq, trade_seq, side, account, security, qty, px, ts)))"
            + " FROM (SELECT * FROM trades ORDER BY part, seq)"),
        "fills", ch.query(ClickHouseStore.FILLS.replace(" FORMAT JSON", " FORMAT TSV"),
            Map.of("account", "103", "day", DAYS[0].toString(), "limit", "100", "offset", "0")),
        "export", "" + ch.query(ClickHouseStore.EXPORT_TRADES, Map.of("day", DAYS[1].toString())).hashCode());
  }

  static String disks(ClickHouseStore ch, String disk) {
    try {
      return ch.query("SELECT count(DISTINCT partition) FROM system.parts WHERE active AND table = 'trades'"
          + " AND disk_name = {d:String}", Map.of("d", disk)).trim();
    } catch (Exception e) { return "?"; }
  }

  static String partitions(ClickHouseStore ch, String disk) throws Exception {
    return ch.query("SELECT arrayStringConcat(arraySort(groupUniqArray(partition)), ',') FROM system.parts WHERE active"
        + " AND table = 'trades' AND disk_name = {d:String}", Map.of("d", disk)).trim();
  }
}
