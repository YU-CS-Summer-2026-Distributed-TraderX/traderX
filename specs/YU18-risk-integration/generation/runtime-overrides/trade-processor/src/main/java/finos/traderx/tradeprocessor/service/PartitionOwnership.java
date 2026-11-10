package finos.traderx.tradeprocessor.service;

import finos.traderx.tradeprocessor.JetStreamFeed;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-DRS-45: which replica reads which feed partitions. N trade-processor replicas each own a disjoint subset of the
 * partitions ({@code PROJECTION_FEED_PARTITIONS_OWNED}); each partition has one row in {@code projection_partition_owner}
 * naming its replica, the replica's whole ownership and a heartbeat (database time). Before any feed reads, this claims
 * the rows of its partitions under a row lock: a row held by another replica whose heartbeat is younger than the TTL is
 * a live owner, and the replica refuses to start (PARTITION_OWNED, exit 3) rather than read that partition twice. A
 * held row is re-checked once after a TTL first, so a crashed predecessor's rows expire instead of being refused.
 * The same replica id (a StatefulSet pod's name) takes its own rows back at once.
 *
 * <p>While running, the heartbeat refreshes the rows every TTL/4. A heartbeat that finds a row taken stops the process
 * (PARTITION_OWNERSHIP_LOST, exit 3). A failed heartbeat (the database away) is retried, not fatal: every replica's
 * rows go stale together and none can claim without the database, so a database restart does not restart the read model.
 * ponytail: the window is a replica cut off from the database past the TTL while another reaches it and claims; the
 * cut-off one learns at its next heartbeat, and a snapshot write could land first. A fence on the snapshot write itself
 * (cursor UPDATE ... WHERE owner = me) closes it, and a process frozen past the TTL too; add it if either is ever seen.
 */
@Service
public class PartitionOwnership implements DisposableBean {
  private static final Logger log = LoggerFactory.getLogger(PartitionOwnership.class);

  /** One partition's row as the claim read it: its holder ("" = nobody), the holder's ownership, heartbeat age. */
  public record Holder(int partition, String replica, String owned, long ageMs) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final int partitions;
  private final String spec;
  private final int[] owned;
  private final String replica;
  private final long ttlMs;
  private final String in;
  private volatile boolean running = true;
  private final Thread heartbeat;

  public PartitionOwnership(JdbcTemplate jdbc, TransactionTemplate transactions,
      @Value("${traderx.feed.partition-count:1}") int partitionCount,
      @Value("${traderx.feed.owned:all}") String spec,
      @Value("${traderx.feed.replica-id:}") String replicaId,
      @Value("${traderx.feed.owner-ttl-ms:20000}") long ttlMs) throws InterruptedException {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.partitions = Math.max(1, partitionCount);
    this.spec = spec == null || spec.isBlank() ? "all" : spec.trim();
    this.replica = replicaId == null || replicaId.isBlank()
        ? java.util.Objects.toString(System.getenv("HOSTNAME"), "local") + ":" + ProcessHandle.current().pid() : replicaId.trim();
    this.ttlMs = ttlMs;
    try {
      this.owned = JetStreamFeed.owned(this.spec, partitions);
      this.in = Arrays.stream(owned).mapToObj(String::valueOf).collect(Collectors.joining(","));
      List<Holder> live = claim();
      if (!live.isEmpty()) {
        log.warn("partition ownership: {} held by a live replica; re-checking in {} ms (a crashed predecessor's rows expire)",
            describe(live), ttlMs);
        Thread.sleep(ttlMs);
        live = claim();
      }
      if (!live.isEmpty()) {
        throw new IllegalStateException("PARTITION_OWNED replica " + replica + " (owns " + this.spec + " of " + partitions
            + ") refused to start: " + describe(live) + ". Each partition is read by exactly one replica (FR-DRS-45): give this"
            + " replica partitions no live replica owns, or stop the owner first.");
      }
    } catch (RuntimeException | InterruptedException refused) {
      JetStreamFeed.exitAfterRefusal("partition ownership", refused.getMessage());
      throw refused;
    }
    log.info("partition ownership: replica {} owns partitions [{}] of {} ({})", replica, in, partitions, this.spec);
    heartbeat = new Thread(this::beat, "partition-ownership-heartbeat");
    heartbeat.setDaemon(true);
    heartbeat.start();
  }

  /** The guard's decision: the rows held by another replica whose heartbeat is younger than the TTL. */
  static List<Holder> liveOthers(List<Holder> rows, String me, long ttlMs) {
    List<Holder> out = new ArrayList<>();
    for (Holder h : rows) {
      if (!h.replica().isEmpty() && !h.replica().equals(me) && h.ageMs() < ttlMs) { out.add(h); }
    }
    return out;
  }

  private static String describe(List<Holder> live) {
    return live.stream().map(h -> "partition " + h.partition() + " is owned by replica " + h.replica() + " (owns " + h.owned()
        + ", heartbeat " + h.ageMs() + " ms ago)").collect(Collectors.joining("; "));
  }

  /** Claim every owned row, or none: returns the live owners found (empty = claimed). */
  private List<Holder> claim() {
    for (int p : owned) {
      jdbc.update("INSERT IGNORE INTO projection_partition_owner(partition_id, replica_id, owned, partition_count, heartbeat_at)"
          + " VALUES (?, '', '', 0, '1970-01-02')", p);
    }
    return transactions.execute(status -> {
      List<Holder> rows = jdbc.query("SELECT partition_id, replica_id, owned, TIMESTAMPDIFF(MICROSECOND, heartbeat_at, NOW(3)) DIV 1000"
          + " FROM projection_partition_owner WHERE partition_id IN (" + in + ") ORDER BY partition_id FOR UPDATE",
          (rs, i) -> new Holder(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
      List<Holder> live = liveOthers(rows, replica, ttlMs);
      if (live.isEmpty()) {
        jdbc.update("UPDATE projection_partition_owner SET replica_id=?, owned=?, partition_count=?, heartbeat_at=NOW(3)"
            + " WHERE partition_id IN (" + in + ")", replica, spec, partitions);
      }
      return live;
    });
  }

  private void beat() {
    long interval = Math.max(250, ttlMs / 4);
    while (running) {
      try { Thread.sleep(interval); } catch (InterruptedException x) { return; }
      try {
        int n = jdbc.update("UPDATE projection_partition_owner SET heartbeat_at=NOW(3) WHERE replica_id=? AND partition_id IN ("
            + in + ")", replica);
        if (n < owned.length) { lost(owned.length - n + " of its partitions are now held by another replica"); return; }
      } catch (RuntimeException x) {
        log.warn("partition ownership heartbeat failed; retrying: {}", x.toString());
      }
    }
  }

  private void lost(String why) {
    if (!running) { return; }   // released: a stopping context's last heartbeat finds no rows of its own
    log.error("ALERT PARTITION_OWNERSHIP_LOST replica {} (owns {}): {}; exiting so no partition is read twice", replica, spec, why);
    System.exit(3);
  }

  public int[] owned() { return owned.clone(); }
  public boolean owns(int partition) { return Arrays.binarySearch(owned, partition) >= 0; }
  public boolean ownsAccount(int accountId) { return owns(Math.floorMod(accountId, partitions)); }
  public String replica() { return replica; }
  public String spec() { return spec; }
  public int partitions() { return partitions; }

  /** The replica holding {@code partition} now ("" = nobody), for a misdirected read's answer. */
  public String ownerOf(int partition) {
    List<String> r = jdbc.queryForList("SELECT replica_id FROM projection_partition_owner WHERE partition_id=?", String.class, partition);
    return r.isEmpty() ? "" : r.get(0);
  }

  /** Every partition's live holder (FR-DRS-46, the order-view read router): a stale or released row is no owner. */
  public java.util.Map<Integer, String> liveOwners() {
    java.util.Map<Integer, String> out = new java.util.HashMap<>();
    jdbc.query("SELECT partition_id, replica_id FROM projection_partition_owner WHERE replica_id <> ''"
        + " AND heartbeat_at > NOW(3) - INTERVAL ? MICROSECOND", rs -> { out.put(rs.getInt(1), rs.getString(2)); }, ttlMs * 1000);
    return out;
  }

  /** Release on a clean stop, so the next owner need not wait out the TTL. The feeds that depend on this stop first. */
  @Override
  public void destroy() {
    running = false;
    heartbeat.interrupt();
    try {
      jdbc.update("UPDATE projection_partition_owner SET replica_id='', heartbeat_at='1970-01-02' WHERE replica_id=?", replica);
    } catch (RuntimeException x) {
      log.warn("partition ownership not released ({}); it expires in {} ms", x.toString(), ttlMs);
    }
  }
}
