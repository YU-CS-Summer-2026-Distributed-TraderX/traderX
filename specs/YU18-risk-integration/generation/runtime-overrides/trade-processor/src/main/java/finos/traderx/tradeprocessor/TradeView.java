package finos.traderx.tradeprocessor;

import finos.traderx.tradeprocessor.model.Position;
import finos.traderx.tradeprocessor.model.Trade;
import finos.traderx.tradeprocessor.model.TradeOrder;
import finos.traderx.tradeprocessor.model.TradeState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * rate (dirty positions, every few seconds) instead of one row per trade side. The trades themselves are
 * the history store's (ADR-102 cutover, 2026-11-08: ClickHouse is their system of record, the blotter reads
 * it); SQL keeps every position and, as exceptions, the sides booking refused ({@code Rejected}), which the
 * stream cannot show.
 *
 * <p>Exact across a restart: a snapshot and its stream cursor are written in one transaction, so a
 * restart loads the snapshot and replays the stream from the cursor. A fill is applied with the SAME
 * booking math as the row-per-trade path ({@code TradeService.book}), handed in as {@code booker}.
 *
 * <p>ponytail: one lock around apply and cut; the cut copies only what changed since the last one.
 */
public final class TradeView {
  /** What one snapshot writes: the changed positions, the sides refused since the last cut, and the cursor. */
  public record Cut(List<Position> positions, List<Trade> rejected, Map<LocalDate, Long> days,
                    long streamSeq, long consensusSeq, long applied, Accounting accounting) {
    public boolean isEmpty() { return positions.isEmpty() && rejected.isEmpty(); }
  }

  /**
   * FR-DRS-29, exactly-once accounting of this partition: every booked side's engine tradeSeq, committed with the
   * cursor. Within a partition the bridge publishes in engine order (one receipt lane per partition), so a tradeSeq at
   * or below the last one booked is a second copy: it is refused and counted, never booked twice. A gate then proves
   * in O(P) that the booked set is exactly {base+1..t}: distinct (strictly increasing here), none above t, t - base of
   * them; the wrapped sums of seq and seq^2 guard the one case a partition cannot see (a side under two accounts).
   */
  public record Accounting(long sides, long maxSeq, long sumSeq, long sumSq, long duplicates, String epoch) {
    public static final Accounting EMPTY = new Accounting(0, 0, 0, 0, 0, "");
  }

  private final Map<String, Position> positions = new HashMap<>();
  private final List<Trade> rejected = new ArrayList<>();
  private final Set<String> dirtyPositions = new HashSet<>();
  // Settlement on the ledger: booked sides per UTC trade date. A side settles when its trade date
  // plus T+N business days has passed, so these counts ARE the settlement state of the partition.
  private final Map<LocalDate, Long> sidesByDay = new HashMap<>();
  private final Set<LocalDate> dirtyDays = new HashSet<>();
  private long streamSeq;
  private int streamIdx = Integer.MAX_VALUE;   // FR-DRS-41: the last record applied of message streamSeq (MAX: all of it)
  // FR-DRS-58: the last record this view was handed and dealt with (booked, or refused as a second copy). The cursor
  // above can be ahead of it after an idle advance; a replay is judged against this, never against the cursor.
  private long handledSeq;
  private int handledIdx = Integer.MAX_VALUE;
  private long idleHead;   // the highest head an idle advance set the cursor to
  private long consensusSeq;
  private long appliedSinceCut;
  private long appliedTotal;
  private long passedByIdle;
  private Accounting acct = Accounting.EMPTY;

  static String key(String scope, Integer account, String security) {
    return scope + "|" + account + "|" + security;
  }

  /** Restore from the last snapshot: its positions and its cursor. */
  public synchronized void load(List<Position> snapshotPositions, long cursorStreamSeq, long cursorConsensusSeq) {
    for (Position p : snapshotPositions) {
      positions.put(key(p.getProjectionScope(), p.getAccountId(), p.getSecurity()), p);
    }
    streamSeq = cursorStreamSeq;
    streamIdx = Integer.MAX_VALUE;
    handledSeq = cursorStreamSeq;
    handledIdx = Integer.MAX_VALUE;
    consensusSeq = cursorConsensusSeq;
  }

  /** Restore the accounting committed with the cursor. */
  public synchronized void loadAccounting(Accounting committed) {
    if (committed != null) { acct = committed; }
  }

  /** Restore the per-trade-date side counts written with the same snapshot as the cursor. */
  public synchronized void loadDays(Map<LocalDate, Long> days) {
    sidesByDay.putAll(days);
  }

  static LocalDate tradeDate(Trade t) {
    return t.getCreated().toInstant().atZone(ZoneOffset.UTC).toLocalDate();
  }

  /** Book one trade side at stream position {@code seq}; {@code booker} mutates the position and
   *  returns the trade row (kept only when Rejected). A seq at or below the cursor is a replay and is ignored. */
  public synchronized boolean apply(TradeOrder order, long seq, BiFunction<TradeOrder, Position, Trade> booker) {
    return apply(order, seq, Integer.MAX_VALUE, booker);
  }

  /**
   * FR-DRS-41: record {@code idx} of the message at stream position {@code seq} (a message holds one or more sides).
   * A record at or before the last one handled, by (seq, idx), is a replay and is ignored. The caller holds this view's
   * monitor across one message's records, so a cut never falls between them: the cursor it carries is a whole message.
   *
   * <p>FR-DRS-58: a record after the last one handled but at or below the cursor was passed by an idle advance
   * ({@link #idle}: the head was read while this message was still undelivered). Nothing booked it, so it is booked
   * here and counted, and the cursor comes back to it: before this it was ignored as a replay, with no log line and no
   * counter, and the partition ended short.
   */
  public synchronized boolean apply(TradeOrder order, long seq, int idx, BiFunction<TradeOrder, Position, Trade> booker) {
    if (seq < handledSeq || (seq == handledSeq && idx <= handledIdx)) { return false; }
    if (seq <= idleHead) { passedByIdle++; }
    handledSeq = seq;
    handledIdx = idx;
    long tradeSeq = TradeFeedHandler.TradeSeqGaps.seqOf(order.getId());
    String epoch = tradeSeq > 0 ? TradeFeedHandler.TradeSeqGaps.epochOf(order.getId()) : acct.epoch();
    if (tradeSeq > 0 && epoch.equals(acct.epoch()) && tradeSeq <= acct.maxSeq()) {
      acct = new Accounting(acct.sides(), acct.maxSeq(), acct.sumSeq(), acct.sumSq(), acct.duplicates() + 1, epoch);
      streamSeq = seq;
      streamIdx = idx;
      return false;
    }
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
    // A Rejected trade (bond fail-closed) reaches the blotter (as an SQL exception row), never a position: not even a
    // flat one.
    if (trade.getState() == TradeState.Rejected) {
      rejected.add(trade);
    } else {
      if (fresh) { positions.put(k, position); }
      dirtyPositions.add(k);
      LocalDate day = tradeDate(trade);
      sidesByDay.merge(day, 1L, Long::sum);
      dirtyDays.add(day);
    }
    if (tradeSeq > 0) {   // a new epoch restarts tradeSeq; the sums keep counting
      acct = new Accounting(acct.sides() + 1, tradeSeq, acct.sumSeq() + tradeSeq, acct.sumSq() + tradeSeq * tradeSeq,
          acct.duplicates(), epoch);
    }
    streamSeq = seq;
    streamIdx = idx;
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
    if (streamLastSeq > streamSeq) { streamSeq = streamLastSeq; streamIdx = Integer.MAX_VALUE; idleHead = streamLastSeq; }
  }

  /** Copy out everything changed since the last cut, with the cursor it is exact at, and reset. */
  public synchronized Cut cut() {
    List<Position> ps = new ArrayList<>(dirtyPositions.size());
    for (String k : dirtyPositions) { ps.add(copy(positions.get(k))); }
    Map<LocalDate, Long> ds = new HashMap<>();
    for (LocalDate d : dirtyDays) { ds.put(d, sidesByDay.get(d)); }
    Cut cut = new Cut(ps, List.copyOf(rejected), ds, streamSeq, consensusSeq, appliedSinceCut, acct);
    dirtyPositions.clear();
    rejected.clear();
    dirtyDays.clear();
    appliedSinceCut = 0;
    return cut;
  }

  /** A snapshot write failed: put its changes back on the dirty list for the next cut. */
  public synchronized void remark(Cut cut) {
    for (Position p : cut.positions()) { dirtyPositions.add(key(p.getProjectionScope(), p.getAccountId(), p.getSecurity())); }
    rejected.addAll(0, cut.rejected());
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

  public synchronized long passedByIdle() { return passedByIdle; }
  public synchronized long streamSeq() { return streamSeq; }
  public synchronized long applied() { return appliedTotal; }
  public synchronized Accounting accounting() { return acct; }
  public synchronized int positionCount() { return positions.size(); }
  public synchronized int dirtyCount() { return dirtyPositions.size(); }
}
