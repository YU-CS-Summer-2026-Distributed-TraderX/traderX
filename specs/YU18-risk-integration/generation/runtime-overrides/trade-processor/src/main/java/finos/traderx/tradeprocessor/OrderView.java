package finos.traderx.tradeprocessor;

import finos.traderx.tradeprocessor.model.OrderUpdate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * YU18 downstream redesign (direction a): the order read model served from MEMORY, fed by the same
 * {@code /orders} egress, instead of one SQL row per lifecycle event. Readers of order state (FIX
 * H/AF via the gateway, {@code GET /accounts/{id}/orders}, the console) only ever ask "what is this
 * account's current state", so that is all that is kept: every open order, plus the last
 * {@code terminalCapacity} terminal ones so a cancel/replace/STP proof can still see the row go
 * CANCELED. Terminal history beyond that belongs to the append-only capture (the kdb tap), not a
 * hot read model: at 400K orders/s it is 400K rows/s forever.
 *
 * <p>Same ordering rule as the SQL projection: a later {@code (consensusSequence, outputOrdinal)}
 * replaces, an earlier one is ignored, a legacy update with no sequence is last-write-wins. A NEW
 * carries the trace id and later updates do not, so the trace id is preserved across updates.
 *
 * <p>ponytail: unbounded in OPEN orders - the engine bounds resting orders (book capacity), not
 * this class. The terminal ring is a count bound, so memory is predictable at any rate: ~850 B per
 * retained update (measured from a heap histogram), so 50K entries are ~42 MB.
 */
public final class OrderView {
  static final Set<String> OPEN = Set.of("NEW", "PARTIALLY_FILLED", "QUEUED", "PENDING_TRIGGER", "SUSPENDED");

  private final ConcurrentHashMap<Integer, ConcurrentHashMap<String, OrderUpdate>> byAccount = new ConcurrentHashMap<>();
  private final ArrayDeque<OrderUpdate> terminals = new ArrayDeque<>();
  private final int terminalCapacity;
  private final AtomicLong applied = new AtomicLong();
  private final AtomicLong ignored = new AtomicLong();

  public OrderView(int terminalCapacity) {
    this.terminalCapacity = Math.max(0, terminalCapacity);
  }

  public void apply(OrderUpdate u) {
    if (u == null || u.getId() == null || u.getAccountId() == null) { return; }
    Map<String, OrderUpdate> orders = byAccount.computeIfAbsent(u.getAccountId(), k -> new ConcurrentHashMap<>());
    // ponytail: an order whose earlier updates this view never held (evicted, or before its first
    // message) reads 0 filled when it ends CANCELED; the SQL projection keeps the row and has no gap.
    u.setFilledQuantity(filled(u.getStatus(), u.getQuantity(), u.getRemainingQuantity(), 0));
    OrderUpdate winner = orders.merge(u.getId(), u, (old, next) -> {
      if (!newer(old, next)) { return old; }
      if (next.getTraceId() == null) { next.setTraceId(old.getTraceId()); }
      next.setFilledQuantity(filled(next.getStatus(), next.getQuantity(), next.getRemainingQuantity(),
          old.getFilledQuantity()));
      return next;
    });
    if (winner != u) { ignored.incrementAndGet(); return; }
    applied.incrementAndGet();
    if (!OPEN.contains(u.getStatus())) { retainTerminal(u); }
  }

  private void retainTerminal(OrderUpdate u) {
    OrderUpdate evict = null;
    synchronized (terminals) {
      terminals.addLast(u);
      if (terminals.size() > terminalCapacity) { evict = terminals.pollFirst(); }
    }
    if (evict != null) {
      Map<String, OrderUpdate> orders = byAccount.get(evict.getAccountId());
      // Only the entry this very update put there: a newer update for the same id stays.
      if (orders != null) { orders.remove(evict.getId(), evict); }
    }
  }

  /**
   * FR-DRS-27: the quantity filled so far, FIX CumQty. Open: quantity - remaining. FILLED: quantity.
   * CANCELED and REJECTED: the engine zeroes remainingQuantity (their LeavesQty), so the fill is the
   * one before this update, {@code before} (0 for an order with no earlier state, null if unknown).
   * Every partial fill is its own update ahead of the cancel, so {@code before} is exact.
   */
  public static Integer filled(String status, Integer quantity, Integer remaining, Integer before) {
    if ("FILLED".equals(status)) { return quantity; }
    if (OPEN.contains(status)) { return quantity == null || remaining == null ? null : quantity - remaining; }
    return before;
  }

  static boolean newer(OrderUpdate old, OrderUpdate next) {
    if (old.getConsensusSequence() == null || next.getConsensusSequence() == null) { return true; }
    int c = Long.compare(next.getConsensusSequence(), old.getConsensusSequence());
    if (c == 0) {
      int a = old.getOutputOrdinal() == null ? -1 : old.getOutputOrdinal();
      int b = next.getOutputOrdinal() == null ? -1 : next.getOutputOrdinal();
      c = Integer.compare(b, a);
    }
    return c > 0;
  }

  public List<OrderUpdate> open(int accountId) { return select(accountId, true); }

  public List<OrderUpdate> all(int accountId) { return select(accountId, false); }

  private List<OrderUpdate> select(int accountId, boolean openOnly) {
    Map<String, OrderUpdate> orders = byAccount.get(accountId);
    if (orders == null) { return List.of(); }
    List<OrderUpdate> out = new ArrayList<>(orders.size());
    for (OrderUpdate u : orders.values()) {
      if (!openOnly || OPEN.contains(u.getStatus())) { out.add(u); }
    }
    return out;
  }

  public long applied() { return applied.get(); }
  public long ignored() { return ignored.get(); }

  public long size() {
    long n = 0;
    for (Map<String, OrderUpdate> m : byAccount.values()) { n += m.size(); }
    return n;
  }
}
