package finos.traderx.tradeprocessor.service;

import com.fasterxml.jackson.databind.*;
import finos.traderx.tradeprocessor.model.*;
import finos.traderx.tradeprocessor.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Automatic managed-run projection catch-up (RI06 automatic projection recovery).
 *
 * <p>One fenced worker across all trade-processor instances pulls bounded, whole-command pages
 * of archive-generated production events after a persisted completeness cursor, and applies each
 * page, its conflict checks, derived positions and the cursor advance in ONE transaction under the
 * existing projection write lock. Live NATS delivery keeps running; overlap is resolved by the
 * existing dedup/immutable checks and by order (sequence, ordinal) monotonicity, and positions of
 * affected keys are recomputed from every retained trade in authoritative order.
 *
 * <p>Never selects, seals, freezes, reopens or activates a run; never deletes or relabels rows.
 * Refusals become BLOCKED with a reason and are re-evaluated at the backoff cadence without
 * mutation. It is not wired to any readiness/liveness probe, so it cannot cause restart loops.
 * Disabled unless {@code projection.auto-recovery.enabled=true}.
 */
@Service
public class AutomaticProjectionRecovery {
  private static final Logger log = LoggerFactory.getLogger(AutomaticProjectionRecovery.class);
  static final int PROTOCOL = 1;
  static final String GENESIS = "0".repeat(64);

  /** Test seam for precise fault points; production registers none. */
  public interface Hooks { void at(String point); }

  static final class Blocked extends RuntimeException {
    Blocked(String reason) { super(reason); }
  }
  static final class Fenced extends RuntimeException {
    Fenced() { super("AUTO_RECOVERY_FENCED"); }
  }
  static final class Superseded extends RuntimeException {
    Superseded(String reason) { super(reason); }
  }

  private final JdbcTemplate jdbc;
  private final RunRegistry registry;
  private final ProjectionWriteLock lock;
  private final RunPeerClient peer;
  private final TradeRepository trades;
  private final OrderRepository orders;
  private final PositionRepository positions;
  private final TradeService tradeService;
  private final OrderProjectionService orderService;
  private final TransactionTemplate tx;
  private final ObjectProvider<Hooks> hooks;
  private final ObjectProvider<finos.traderx.messaging.nats.NatsJSONSubscriber<?>> subscribers;
  private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  final boolean enabled;
  final List<String> endpoints;
  final long intervalMs, leaseTtlMs, maxBackoffMs;
  final int pageEvents, maxPagesPerCycle;
  final String ownerId = "tp-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 8);

  private final Object wake = new Object();
  private volatile boolean running;
  private volatile boolean stopped; // cancellation: set once on shutdown
  private volatile boolean triggered;
  private Thread worker;
  // Liveness only; completeness is the persisted cursor.
  private volatile long fence = -1;
  private volatile boolean owner;
  private volatile long lastTickMillis, nextAttemptMillis;
  private volatile String lastError;
  private int failures;

  public AutomaticProjectionRecovery(JdbcTemplate jdbc, RunRegistry registry, ProjectionWriteLock lock, RunPeerClient peer,
      TradeRepository trades, OrderRepository orders, PositionRepository positions, TradeService tradeService,
      OrderProjectionService orderService, PlatformTransactionManager manager, ObjectProvider<Hooks> hooks,
      ObjectProvider<finos.traderx.messaging.nats.NatsJSONSubscriber<?>> subscribers,
      @Value("${projection.auto-recovery.enabled:false}") boolean enabled,
      @Value("${projection.auto-recovery.endpoints:}") String endpoints,
      @Value("${projection.auto-recovery.interval-ms:15000}") long intervalMs,
      @Value("${projection.auto-recovery.lease-ttl-ms:30000}") long leaseTtlMs,
      @Value("${projection.auto-recovery.max-backoff-ms:300000}") long maxBackoffMs,
      @Value("${projection.auto-recovery.page-events:500}") int pageEvents,
      @Value("${projection.auto-recovery.max-pages-per-cycle:50}") int maxPagesPerCycle) {
    if (intervalMs < 100 || leaseTtlMs < 1000 || maxBackoffMs < intervalMs || pageEvents < 1 || pageEvents > 10000
        || maxPagesPerCycle < 1 || maxPagesPerCycle > 10000) {
      throw new IllegalArgumentException("AUTO_RECOVERY_CONFIGURATION_INVALID");
    }
    this.jdbc = jdbc; this.registry = registry; this.lock = lock; this.peer = peer; this.trades = trades;
    this.orders = orders; this.positions = positions; this.tradeService = tradeService; this.orderService = orderService;
    this.tx = new TransactionTemplate(manager); this.hooks = hooks; this.subscribers = subscribers;
    this.enabled = enabled; this.intervalMs = intervalMs; this.leaseTtlMs = leaseTtlMs; this.maxBackoffMs = maxBackoffMs;
    this.pageEvents = pageEvents; this.maxPagesPerCycle = maxPagesPerCycle;
    this.endpoints = Arrays.stream(endpoints.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    if (enabled && this.endpoints.isEmpty()) { throw new IllegalArgumentException("AUTO_RECOVERY_ENDPOINTS_REQUIRED"); }
  }

  private void hook(String point) { Hooks h = hooks.getIfAvailable(); if (h != null) { h.at(point); } }

  // ---------------------------------------------------------------- scheduling

  @EventListener(ApplicationReadyEvent.class)
  public synchronized void start() {
    if (!enabled || running) { return; }
    running = true; triggered = true; // startup is a trigger
    worker = new Thread(this::loop, "projection-auto-recovery");
    worker.setDaemon(true);
    worker.start();
  }

  @jakarta.annotation.PreDestroy
  public void stop() {
    Thread t;
    synchronized (this) { running = false; stopped = true; t = worker; }
    if (t == null) { return; }
    t.interrupt();
    try { t.join(10000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    try { jdbc.update("UPDATE projection_catchup_lease SET owner_id=NULL,expires_at=NULL WHERE singleton_id=1 AND owner_id=?", ownerId); }
    catch (DataAccessException ex) { log.warn("auto recovery lease release failed: {}", ex.getMessage()); }
  }

  /** Request an immediate cycle (startup, NATS reconnect). */
  public void trigger() { synchronized (wake) { triggered = true; wake.notifyAll(); } }

  private void loop() {
    Map<Object, Boolean> wasConnected = new IdentityHashMap<>();
    nextAttemptMillis = System.currentTimeMillis();
    while (running) {
      try {
        // Reconnect edge of any projection subscriber is a trigger; periodic cadence is the
        // independent path that does not depend on the notification path that failed.
        for (var s : subscribers) {
          boolean now = s.isConnected();
          Boolean before = wasConnected.put(s, now);
          if (now && Boolean.FALSE.equals(before)) { triggered = true; log.info("auto recovery: NATS reconnect trigger"); }
        }
        long now = System.currentTimeMillis();
        if (triggered || now >= nextAttemptMillis) {
          triggered = false;
          lastTickMillis = now;
          boolean ok = cycle();
          failures = ok ? 0 : Math.min(failures + 1, 30);
          long delay = ok ? intervalMs : Math.min(maxBackoffMs, intervalMs << Math.min(failures, 20));
          nextAttemptMillis = System.currentTimeMillis() + delay;
        }
        synchronized (wake) { if (!triggered) { wake.wait(Math.max(1, Math.min(1000, nextAttemptMillis - System.currentTimeMillis()))); } }
      } catch (InterruptedException ex) {
        return;
      } catch (RuntimeException ex) {
        log.error("auto recovery loop error", ex); // never kills the worker
      }
    }
  }

  // ---------------------------------------------------------------- one cycle

  /** @return true when the cycle ended without error (including standby / nothing to do). */
  boolean cycle() {
    String scope = null;
    try {
      if (!acquireLease()) { owner = false; log.debug("auto recovery standby: lease held by another owner"); return true; }
      owner = true;
      scope = registry.activeScope();
      Map<String, Object> run = registry.scope(scope);
      if (!"epoch-v1".equals(run.get("event_id_scheme")) || !Set.of("ACTIVE", "DRAINING").contains(run.get("phase"))) {
        log.debug("auto recovery: selected scope {} is not an ACTIVE/DRAINING epoch-v1 run", scope);
        lastError = null; return true; // unknown legacy / sealed: never automatic, nothing claimed
      }
      String hash = (String) run.get("descriptor_hash");
      String endpoint = endpointFor(scope, hash);
      bootstrap(scope, hash);
      for (int pageNo = 0; pageNo < maxPagesPerCycle && !stopped; pageNo++) {
        if (!acquireLease()) { throw new Fenced(); }
        long after = cursorThrough(scope);
        JsonNode page = peer.catchupEvents(endpoint, after, pageEvents);
        hook("fetched");
        if (page.path("idle").asBoolean(false)) { markIdle(scope, hash, page); break; }
        boolean complete = apply(scope, hash, page);
        hook("after-commit");
        if (complete) { break; }
      }
      lastError = null;
      return true;
    } catch (Fenced ex) {
      owner = false; lastError = ex.getMessage(); log.warn("auto recovery fenced out: owner={}", ownerId); return true;
    } catch (Superseded ex) {
      lastError = ex.getMessage(); log.info("auto recovery: scope {} no longer selected/writable: {}", scope, ex.getMessage()); return true;
    } catch (Blocked ex) {
      lastError = ex.getMessage(); log.error("auto recovery BLOCKED scope={} reason={}", scope, ex.getMessage());
      mark(scope, "BLOCKED", ex.getMessage()); return false;
    } catch (RuntimeException ex) {
      String reason = String.valueOf(ex.getMessage());
      if (reason.contains("RUN_PEER_INTERRUPTED") && stopped) { return true; }
      boolean refusal = reason.startsWith("RECOVERY_PEER_REFUSED: HTTP 500") || reason.startsWith("RECOVERY_PEER_REFUSED: HTTP 401");
      lastError = reason; log.warn("auto recovery {} scope={}: {}", refusal ? "BLOCKED" : "retrying", scope, reason);
      mark(scope, refusal ? "BLOCKED" : "RETRYING", reason); return false;
    }
  }

  private String endpointFor(String scope, String hash) {
    for (String endpoint : endpoints) {
      try {
        JsonNode status = peer.status(endpoint);
        if (hash.equals(status.path("descriptorHash").asText()) && scope.equals(status.path("projectionScope").asText())) {
          return endpoint;
        }
      } catch (RuntimeException ex) {
        log.debug("auto recovery endpoint {} unavailable: {}", endpoint, ex.getMessage());
      }
    }
    throw new IllegalStateException("AUTO_RECOVERY_SOURCE_UNAVAILABLE: no configured endpoint serves " + scope);
  }

  // ---------------------------------------------------------------- lease / fencing

  /** Acquire or renew. Takeover (new owner, or re-acquire after release/expiry) bumps the fence. */
  boolean acquireLease() {
    int updated = jdbc.update("UPDATE projection_catchup_lease SET fence=IF(owner_id<=>? AND expires_at>=NOW(6),fence,fence+1),"
        + "owner_id=?,expires_at=NOW(6)+INTERVAL ? MICROSECOND WHERE singleton_id=1 "
        + "AND (owner_id IS NULL OR owner_id=? OR expires_at IS NULL OR expires_at<NOW(6))",
        ownerId, ownerId, leaseTtlMs * 1000, ownerId);
    if (updated != 1) { fence = -1; return false; }
    fence = jdbc.queryForObject("SELECT fence FROM projection_catchup_lease WHERE singleton_id=1 AND owner_id=?", Long.class, ownerId);
    return true;
  }

  /**
   * First statement of every worker transaction. A paused/stopped owner inside its transaction
   * would otherwise hold the lease row and the global projection write lock forever (measured:
   * no takeover within 180 s, live booking on the other instance timing out). The server aborts a
   * session idle inside a transaction for longer than the lease TTL and rolls it back, which
   * releases both locks; the stale process can then never commit on that dead session.
   * Note: the pooled connection keeps this session value for later use by other writers.
   */
  private void boundIdleTransaction() {
    jdbc.execute("SET SESSION idle_transaction_timeout=" + Math.max(1, leaseTtlMs / 1000));
  }

  /** Inside a transaction: the row lock is held until commit, so no takeover can interleave. */
  private void requireOwner(long expectedFence) {
    boundIdleTransaction();
    var rows = jdbc.queryForList("SELECT owner_id,fence,expires_at>=NOW(6) AS live FROM projection_catchup_lease WHERE singleton_id=1 FOR UPDATE");
    if (rows.size() != 1) { throw new Blocked("AUTO_RECOVERY_LEASE_MISSING"); }
    var r = rows.get(0);
    if (!ownerId.equals(r.get("owner_id")) || ((Number) r.get("fence")).longValue() != expectedFence
        || r.get("live") == null || ((Number) r.get("live")).intValue() != 1) {
      throw new Fenced();
    }
  }

  // ---------------------------------------------------------------- cursor

  private long cursorThrough(String scope) {
    return jdbc.queryForObject("SELECT verified_through_seq FROM projection_catchup_cursor WHERE projection_scope=?", Long.class, scope);
  }

  /** Fresh automatic cursor always starts at genesis and re-verifies every retained row; an
   * explicit-recovery checkpoint is never a starting point (it lacks chain coordinates), only an
   * identity cross-check. Incompatible identity/protocol refuses instead of guessing. */
  private void bootstrap(String scope, String hash) {
    long f = fence;
    tx.executeWithoutResult(status -> {
      requireOwner(f);
      var rows = jdbc.queryForList("SELECT descriptor_hash,protocol_version FROM projection_catchup_cursor WHERE projection_scope=? FOR UPDATE", scope);
      if (rows.isEmpty()) {
        var explicit = jdbc.queryForList("SELECT descriptor_hash FROM projection_recovery WHERE projection_scope=?", scope);
        if (!explicit.isEmpty() && !hash.equals(explicit.get(0).get("descriptor_hash"))) {
          throw new Blocked("AUTO_RECOVERY_CHECKPOINT_IDENTITY_CONFLICT: " + scope);
        }
        jdbc.update("INSERT INTO projection_catchup_cursor(projection_scope,descriptor_hash,protocol_version,verified_through_seq,"
            + "verified_digest,verified_event_count,source_boundary_seq,state,updated_by_fence) VALUES (?,?,?,0,?,0,0,'CATCHING_UP',?)",
            scope, hash, PROTOCOL, GENESIS, f);
      } else if (!hash.equals(rows.get(0).get("descriptor_hash")) || ((Number) rows.get(0).get("protocol_version")).intValue() != PROTOCOL) {
        throw new Blocked("AUTO_RECOVERY_CURSOR_IDENTITY_CONFLICT: " + scope);
      }
    });
  }

  private void mark(String scope, String state, String reason) {
    if (scope == null || fence < 0) { return; }
    long f = fence;
    try {
      tx.executeWithoutResult(status -> {
        requireOwner(f);
        jdbc.update("UPDATE projection_catchup_cursor SET state=?,blocked_reason=?,last_attempt_at=NOW(6),updated_by_fence=? WHERE projection_scope=?",
            state, reason == null ? null : reason.substring(0, Math.min(512, reason.length())), f, scope);
      });
    } catch (RuntimeException ex) {
      log.warn("auto recovery could not record state {}: {}", state, ex.getMessage());
    }
  }

  /** Source applied sequence equals the verified cursor: nothing new was committed. */
  private void markIdle(String scope, String hash, JsonNode page) {
    long f = fence;
    tx.executeWithoutResult(status -> {
      requireOwner(f);
      if (!hash.equals(page.path("descriptorHash").asText()) || !scope.equals(page.path("projectionScope").asText())) {
        throw new Blocked("AUTO_RECOVERY_SOURCE_IDENTITY_MISMATCH");
      }
      int n = jdbc.update("UPDATE projection_catchup_cursor SET state='CURRENT',blocked_reason=NULL,source_boundary_seq=?,"
          + "last_attempt_at=NOW(6),last_verified_at=NOW(6),updated_by_fence=? WHERE projection_scope=? AND verified_through_seq=?",
          page.path("boundarySeq").asLong(), f, scope, page.path("afterSeq").asLong());
      if (n != 1) { throw new IllegalStateException("AUTO_RECOVERY_CURSOR_MOVED"); }
    });
  }

  // ---------------------------------------------------------------- one page, one transaction

  private static void require(boolean ok, String reason) { if (!ok) { throw new Blocked(reason); } }

  static String chain(String digest, String wire) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((digest + "\n" + wire).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
  }

  /** @return whether the page reached the source boundary it was cut at. */
  boolean apply(String scope, String hash, JsonNode page) {
    long f = fence;
    Boolean complete = tx.execute(status -> {
      boundIdleTransaction();
      lock.acquire();
      requireOwner(f);
      var run = registry.scope(scope);
      if (!scope.equals(registry.activeScope()) || !Set.of("ACTIVE", "DRAINING").contains(run.get("phase"))
          || !hash.equals(run.get("descriptor_hash"))) {
        throw new Superseded("RECOVERY_SELECTED_WRITABLE_SCOPE_REQUIRED");
      }
      require(page.path("protocol").asInt() == PROTOCOL && hash.equals(page.path("descriptorHash").asText())
          && scope.equals(page.path("projectionScope").asText()), "AUTO_RECOVERY_SOURCE_IDENTITY_MISMATCH");
      var cursor = jdbc.queryForMap("SELECT * FROM projection_catchup_cursor WHERE projection_scope=? FOR UPDATE", scope);
      long after = page.path("afterSeq").asLong(-1), through = page.path("throughSeq").asLong(-1), boundary = page.path("boundarySeq").asLong(-1);
      if (((Number) cursor.get("verified_through_seq")).longValue() != after) { throw new IllegalStateException("AUTO_RECOVERY_CURSOR_MOVED"); }
      require(cursor.get("verified_digest").equals(page.path("afterDigest").asText()), "AUTO_RECOVERY_SOURCE_HISTORY_CHANGED: through=" + after);
      require(through > after && through <= boundary && page.path("events").isArray(), "AUTO_RECOVERY_PAGE_INVALID");

      // Chain proof over the exact production wire bytes, then parse.
      String digest = (String) cursor.get("verified_digest");
      Map<String, TradeOrder> pageTrades = new LinkedHashMap<>();
      List<OrderUpdate> pageOrders = new ArrayList<>();
      Map<String, OrderUpdate> orderAt = new HashMap<>();
      Map<String, OrderUpdate> pageOrderById = new HashMap<>();
      long sequence = after;
      Map<Long, Integer> lastOrdinal = new HashMap<>();
      try {
        for (JsonNode w : page.path("events")) {
          require(w.isTextual(), "AUTO_RECOVERY_PAGE_INVALID");
          String wire = w.asText();
          digest = chain(digest, wire);
          JsonNode envelope = mapper.readTree(wire);
          ScopedEvent parsed;
          if ("TradeOrder".equals(envelope.path("type").asText())) {
            TradeOrder t = mapper.treeToValue(envelope.path("payload"), TradeOrder.class);
            require(t.getAccountId() != null && t.getSecurity() != null && t.getSide() != null && t.getQuantity() != null
                && t.getQuantity() > 0 && t.getPrice() != null && t.getPrice().signum() > 0 && t.getPrice().scale() <= 6,
                "RECOVERY_TRADE_ECONOMICS_INVALID");
            require(pageTrades.put(t.getId(), t) == null, "RECOVERY_DUPLICATE_SOURCE_TRADE: " + t.getId());
            parsed = t;
          } else if ("OrderUpdate".equals(envelope.path("type").asText())) {
            OrderUpdate o = mapper.treeToValue(envelope.path("payload"), OrderUpdate.class);
            registry.validate(o);
            Integer previous = lastOrdinal.put(o.getConsensusSequence(), o.getOutputOrdinal());
            require(previous == null || o.getOutputOrdinal() > previous, "RECOVERY_ORDER_EVENT_ORDER_INVALID");
            require(orderAt.put(o.getId() + "@" + o.getConsensusSequence() + ":" + o.getOutputOrdinal(), o) == null,
                "RECOVERY_DUPLICATE_SOURCE_ORDER: " + o.getId());
            OrderUpdate prior = pageOrderById.put(o.getId(), o);
            require(prior == null || (Objects.equals(prior.getAccountId(), o.getAccountId()) && Objects.equals(prior.getSecurity(), o.getSecurity())
                && Objects.equals(prior.getSide(), o.getSide())), "RECOVERY_SOURCE_ORDER_PROVENANCE_CONFLICT: " + o.getId());
            pageOrders.add(o);
            parsed = o;
          } else {
            throw new Blocked("RECOVERY_EVENT_TYPE_UNKNOWN");
          }
          try { registry.validate(parsed); } catch (IllegalArgumentException ex) { throw new Blocked(ex.getMessage()); }
          require(scope.equals(parsed.getProjectionScope()) && parsed.getConsensusSequence() > after
              && parsed.getConsensusSequence() >= sequence && parsed.getConsensusSequence() <= through, "RECOVERY_SOURCE_ORDER_OR_SCOPE_INVALID");
          try { registry.requireWritable(parsed); } catch (IllegalStateException ex) { throw new Blocked(ex.getMessage()); }
          sequence = parsed.getConsensusSequence();
        }
      } catch (java.io.IOException ex) {
        throw new Blocked("RECOVERY_EVENT_JSON");
      }
      require(digest.equals(page.path("throughDigest").asText()), "AUTO_RECOVERY_PAGE_DIGEST_MISMATCH");

      try {
        // Every retained trade positioned in this window must be a source trade (no orphans).
        for (Trade t : trades.findByProjectionScopeAndConsensusSequenceBetween(scope, after + 1, through)) {
          require(pageTrades.containsKey(t.getId()), "RECOVERY_ORPHAN_OR_AHEAD_TRADE: " + t.getId());
        }
        Map<String, Trade> existing = new HashMap<>();
        for (Trade t : trades.findAllById(pageTrades.keySet())) { existing.put(t.getId(), t); }
        List<TradeOrder> missing = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (TradeOrder t : pageTrades.values()) {
          Trade retained = existing.get(t.getId());
          if (retained != null) {
            TradeService.requireSameTrade(retained, t);
            require(retained.getState() != TradeState.Rejected, "RECOVERY_REJECTED_BOOKING_REQUIRES_REVIEW: " + t.getId());
          } else {
            missing.add(t);
          }
          // Every key the page touches is reconciled, not only keys with inserted trades: trades
          // that all arrived live but out of order leave an arrival-order basis (review R1).
          keys.add(t.getAccountId() + ":" + t.getSecurity());
          // Trade-to-order provenance: the source order is in this page or already retained.
          OrderUpdate o = pageOrderById.get(t.getSourceOrderId());
          OrderRow row = o == null ? orders.findById(t.getSourceOrderId()).orElse(null) : null;
          require((o != null && Objects.equals(o.getAccountId(), t.getAccountId()) && Objects.equals(o.getSecurity(), t.getSecurity())
                  && Objects.equals(o.getSide(), t.getSide().name()))
              || (row != null && scope.equals(row.getProjectionScope()) && Objects.equals(row.getAccountId(), t.getAccountId())
                  && Objects.equals(row.getSecurity(), t.getSecurity()) && Objects.equals(row.getSide(), t.getSide().name())),
              "RECOVERY_TRADE_ORDER_PROVENANCE_CONFLICT: " + t.getId());
        }
        // Retained order rows positioned in this window must be the exact SQL representation of
        // the source event there. Checked BEFORE this page's writes: afterwards the persistence
        // context would return unrounded in-memory values rather than what the column stores.
        Map<String, Integer> precision = EventRecoveryService.orderTimestampPrecision(jdbc);
        for (OrderRow row : orders.findByProjectionScopeAndConsensusSequenceBetween(scope, after + 1, through)) {
          EventRecoveryService.requireRetainedOrderMatches(jdbc, mapper, precision, row,
              orderAt.get(row.getId() + "@" + row.getConsensusSequence() + ":" + row.getOutputOrdinal()));
        }
        // Retained balances of every page key must be explained by retained trades before the
        // derived quantity/basis is re-derived; the pre-page value drives notifications (R2).
        Map<String, Object[]> before = new HashMap<>();
        for (String key : keys) {
          int colon = key.indexOf(':');
          Integer account = Integer.valueOf(key.substring(0, colon));
          String security = key.substring(colon + 1);
          long signed = 0; boolean any = false;
          for (Trade t : trades.findByProjectionScopeAndAccountId(scope, account)) {
            if (!security.equals(t.getSecurity())) { continue; }
            require(t.getState() != TradeState.Rejected, "RECOVERY_REJECTED_BOOKING_REQUIRES_REVIEW: " + t.getId());
            signed = Math.addExact(signed, Math.multiplyExact(t.getQuantity().longValue(), t.getSide() == TradeSide.Buy ? 1L : -1L));
            any = true;
          }
          Position p = positions.findByProjectionScopeAndAccountIdAndSecurity(scope, account, security);
          before.put(key, p == null ? null : new Object[] {p.getQuantity(), p.getAverageCostBasis()});
          if (p == null) {
            require(!any, "RECOVERY_RETAINED_POSITION_MISSING: " + key);
          } else {
            require(p.getQuantity() != null && p.getQuantity().longValue() == signed, "RECOVERY_RETAINED_POSITION_QUANTITY_CONFLICT: " + key);
            if (!any) {
              require(p.getAverageCostBasis() != null && p.getAverageCostBasis().signum() == 0, "RECOVERY_UNATTRIBUTED_POSITION_BASIS: " + key);
            }
          }
        }
        if (!missing.isEmpty()) {
          for (TradeBookingResult r : tradeService.processRecoveredTrades(missing)) {
            require(r.getTrade().getState() != TradeState.Rejected, "RECOVERY_REJECTED_BOOKING_REQUIRES_REVIEW: " + r.getTrade().getId());
          }
        }
        if (!keys.isEmpty()) { tradeService.rebuildRetainedPositions(scope, keys, before); }
        for (OrderUpdate o : pageOrders) { orderService.persist(o); } // never rewinds a newer live version
        boolean done = through == boundary;
        int n = jdbc.update("UPDATE projection_catchup_cursor SET verified_through_seq=?,verified_digest=?,"
            + "verified_event_count=verified_event_count+?,source_boundary_seq=GREATEST(source_boundary_seq,?),state=?,blocked_reason=NULL,"
            + "last_attempt_at=NOW(6),last_verified_at=NOW(6),updated_by_fence=? WHERE projection_scope=? AND verified_through_seq=?",
            through, digest, page.path("events").size(), boundary, done ? "CURRENT" : "CATCHING_UP", f, scope, after);
        if (n != 1) { throw new IllegalStateException("AUTO_RECOVERY_CURSOR_MOVED"); }
        jdbc.update("INSERT INTO projection_catchup_log(projection_scope,after_seq,through_seq,event_count,inserted_trades,owner_id,fence,committed_at) "
            + "VALUES (?,?,?,?,?,?,?,NOW(6))", scope, after, through, page.path("events").size(), missing.size(), ownerId, f);
        hook("before-commit");
        log.info("AUTO_RECOVERY_PAGE scope={} after={} through={} boundary={} events={} insertedTrades={} fence={}",
            scope, after, through, boundary, page.path("events").size(), missing.size(), f);
        return done;
      } catch (Blocked | Fenced | Superseded ex) {
        throw ex;
      } catch (IllegalArgumentException ex) {
        throw new Blocked(ex.getMessage()); // immutable conflicts from the shared booking/order checks
      } catch (IllegalStateException ex) {
        if (String.valueOf(ex.getMessage()).startsWith("AUTO_RECOVERY_CURSOR_MOVED")) { throw ex; }
        throw new Blocked(ex.getMessage());
      }
    });
    return Boolean.TRUE.equals(complete);
  }

  // ---------------------------------------------------------------- read-only status

  public Map<String, Object> status() {
    Map<String, Object> worker = new LinkedHashMap<>();
    worker.put("enabled", enabled);
    worker.put("running", running);
    worker.put("ownerId", ownerId);
    worker.put("isLeaseOwner", owner);
    worker.put("fence", fence);
    worker.put("lastTickMillis", lastTickMillis);
    worker.put("nextAttemptMillis", nextAttemptMillis);
    worker.put("lastError", lastError);
    List<Map<String, Object>> scopes = new ArrayList<>();
    String active = null;
    try { active = registry.activeScope(); } catch (RuntimeException ignored) { /* reported as null */ }
    try {
      for (var row : jdbc.queryForList("SELECT c.projection_scope,c.verified_through_seq,c.source_boundary_seq,c.state,c.blocked_reason,"
          + "c.last_verified_at,c.last_attempt_at,c.verified_event_count,r.phase FROM projection_catchup_cursor c "
          + "JOIN projection_runs r ON r.projection_scope=c.projection_scope ORDER BY c.projection_scope")) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("projectionScope", row.get("projection_scope"));
        s.put("selected", Objects.equals(active, row.get("projection_scope")));
        s.put("runPhase", row.get("phase"));
        s.put("state", row.get("state"));
        s.put("blockedReason", row.get("blocked_reason"));
        s.put("verifiedThroughSeq", row.get("verified_through_seq"));
        s.put("verifiedEventCount", row.get("verified_event_count"));
        s.put("lastObservedSourceBoundarySeq", row.get("source_boundary_seq"));
        s.put("lastSuccessfulVerificationAt", String.valueOf(row.get("last_verified_at")));
        s.put("lastAttemptAt", String.valueOf(row.get("last_attempt_at")));
        scopes.add(s);
      }
    } catch (DataAccessException ex) {
      worker.put("statusError", "AUTO_RECOVERY_SCHEMA_UNAVAILABLE");
    }
    return Map.of("completeness", scopes, "worker", worker,
        "semantics", "completeness is point-in-time: every source event through verifiedThroughSeq was verified at "
            + "lastSuccessfulVerificationAt; worker fields are liveness only and never imply current completeness");
  }
}
