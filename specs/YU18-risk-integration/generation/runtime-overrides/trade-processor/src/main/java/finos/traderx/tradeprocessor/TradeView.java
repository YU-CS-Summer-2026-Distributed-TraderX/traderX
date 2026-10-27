package finos.traderx.tradeprocessor;

import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.model.TradeState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * YU18 downstream redesign, the trade store (docs/engineering/downstream-at-400k.md §7): the trade
 * projection kept in MEMORY and written to SQL as periodic SNAPSHOTS, so the database sees a bounded
 * rate (dirty positions plus each dirty account's blotter window, every few seconds) instead of one
 * row per trade side. The replayable stream is the system of record for the trades themselves; SQL
 * keeps what its readers need: every position, and the last {@code blotterDepth} fills per account.
 *
 * <p>Exact across a restart: a snapshot and its stream cursor are written in one transaction, so a
 * restart loads the snapshot and replays the stream from the cursor. A fill is applied with the SAME
 * booking math as the row-per-trade path ({@code TradeService.book}), handed in as {@code booker}.
 *
 * <p>ponytail: one lock around apply and cut; the cut copies only what changed since the last one.
 */
public final class TradeView {
  /** What one snapshot writes: the changed positions, the changed accounts' windows, and the cursor. */
  public record Cut(List<Position> positions, Map<Integer, List<Trade>> blotters, Map<LocalDate, Long> days,
                    long streamSeq, long consensusSeq, long applied) {
    public boolean isEmpty() { return positions.isEmpty() && blotters.isEmpty(); }
  }

  private final int blotterDepth;
  private final Map<String, Position> positions = new HashMap<>();
  private final Map<Integer, ArrayDeque<Trade>> blotter = new HashMap<>();
  private final Set<String> dirtyPositions = new HashSet<>();
  private final Set<Integer> dirtyAccounts = new HashSet<>();
  // Settlement on the ledger: booked sides per UTC trade date. A side settles when its trade date
  // plus T+N business days has passed, so these counts ARE the settlement state of the partition.
  private final Map<LocalDate, Long> sidesByDay = new HashMap<>();
  private final Set<LocalDate> dirtyDays = new HashSet<>();
  private long streamSeq;
  private long consensusSeq;
  private long appliedSinceCut;
  private long appliedTotal;

  public TradeView(int blotterDepth) {
    this.blotterDepth = Math.max(1, blotterDepth);
  }

  static String key(String scope, Integer account, String security) {
    return scope + "|" + account + "|" + security;
  }

  /** Restore from the last snapshot: its positions, its blotter rows (oldest first) and its cursor. */
  public synchronized void load(List<Position> snapshotPositions, List<Trade> snapshotTrades,
                                long cursorStreamSeq, long cursorConsensusSeq) {
    for (Position p : snapshotPositions) {
      positions.put(key(p.getProjectionScope(), p.getAccountId(), p.getSecurity()), p);
    }
    for (Trade t : snapshotTrades) {
      ArrayDeque<Trade> ring = blotter.computeIfAbsent(t.getAccountId(), k -> new ArrayDeque<>());
      ring.addLast(t);
      while (ring.size() > blotterDepth) { ring.pollFirst(); }
    }
    streamSeq = cursorStreamSeq;
    consensusSeq = cursorConsensusSeq;
  }

  /** Restore the per-trade-date side counts written with the same snapshot as the cursor. */
  public synchronized void loadDays(Map<LocalDate, Long> days) {
    sidesByDay.putAll(days);
  }

  static LocalDate tradeDate(Trade t) {
    return t.getCreated().toInstant().atZone(ZoneOffset.UTC).toLocalDate();
  }

  /** Book one trade side at stream position {@code seq}; {@code booker} mutates the position and
   *  returns the trade row the window keeps. A seq at or below the cursor is a replay and is ignored. */
  public synchronized boolean apply(TradeOrder order, long seq, BiFunction<TradeOrder, Position, Trade> booker) {
    if (seq <= streamSeq) { return false; }
    String k = key(order.getProjectionScope(), order.getAccountId(), order.getSecurity());
    Position position = positions.get(k);
    boolean fresh = position == null;
    if (fresh) {
      position = new Position();
      position.setProjectionScope(order.getProjectionScope());
      position.setAccountId(order.getAccountId());
      position.setSecurity(order.getSecurity());
      position.setQuantity(0);
      position.setAverageCostBasis(BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP));
    }
    Trade trade = booker.apply(order, position);
    // A Rejected trade (bond fail-closed) reaches the blotter, never a position: not even a flat one.
    if (trade.getState() != TradeState.Rejected) {
      if (fresh) { positions.put(k, position); }
      dirtyPositions.add(k);
      LocalDate day = tradeDate(trade);
      sidesByDay.merge(day, 1L, Long::sum);
      dirtyDays.add(day);
    }
    ArrayDeque<Trade> ring = blotter.computeIfAbsent(order.getAccountId(), a -> new ArrayDeque<>());
    ring.addLast(trade);
    if (ring.size() > blotterDepth) { ring.pollFirst(); }
    dirtyAccounts.add(order.getAccountId());
    streamSeq = seq;
    if (order.getConsensusSequence() != null) { consensusSeq = Math.max(consensusSeq, order.getConsensusSequence()); }
    appliedSinceCut++;
    appliedTotal++;
    return true;
  }

  /**
   * The feed is caught up and the stream holds no message for this partition up to {@code streamLastSeq}
   * (called by the pull thread, after an empty fetch, with the consumer's pending at 0): the cursor may
   * move there. Without this an idle partition's cursor ages out of retention while nothing was lost.
   */
  public synchronized void idle(long streamLastSeq) {
    if (streamLastSeq > streamSeq) { streamSeq = streamLastSeq; }
  }

  /** Copy out everything changed since the last cut, with the cursor it is exact at, and reset. */
  public synchronized Cut cut() {
    List<Position> ps = new ArrayList<>(dirtyPositions.size());
    for (String k : dirtyPositions) { ps.add(copy(positions.get(k))); }
    Map<Integer, List<Trade>> bs = new HashMap<>();
    for (Integer a : dirtyAccounts) { bs.put(a, new ArrayList<>(blotter.get(a))); }
    Map<LocalDate, Long> ds = new HashMap<>();
    for (LocalDate d : dirtyDays) { ds.put(d, sidesByDay.get(d)); }
    Cut cut = new Cut(ps, bs, ds, streamSeq, consensusSeq, appliedSinceCut);
    dirtyPositions.clear();
    dirtyAccounts.clear();
    dirtyDays.clear();
    appliedSinceCut = 0;
    return cut;
  }

  /** A snapshot write failed: put its changes back on the dirty list for the next cut. */
  public synchronized void remark(Cut cut) {
    for (Position p : cut.positions()) { dirtyPositions.add(key(p.getProjectionScope(), p.getAccountId(), p.getSecurity())); }
    dirtyAccounts.addAll(cut.blotters().keySet());
    dirtyDays.addAll(cut.days().keySet());
    appliedSinceCut += cut.applied();
  }

  private static Position copy(Position p) {
    Position c = new Position();
    c.setProjectionScope(p.getProjectionScope());
    c.setAccountId(p.getAccountId());
    c.setSecurity(p.getSecurity());
    c.setQuantity(p.getQuantity());
    c.setAverageCostBasis(p.getAverageCostBasis());
    c.setUpdated(p.getUpdated());
    return c;
  }

  public synchronized long streamSeq() { return streamSeq; }
  public synchronized long applied() { return appliedTotal; }
  public synchronized int positionCount() { return positions.size(); }
  public synchronized int dirtyCount() { return dirtyPositions.size(); }
}
