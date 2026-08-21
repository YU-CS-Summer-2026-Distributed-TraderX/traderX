package finos.traderx.algoengine.eventstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import finos.traderx.algoengine.eventstore.AlgoEventStore.Recovery;
import finos.traderx.algoengine.eventstore.AlgoEventStore.Verdict;
import finos.traderx.algoengine.model.AlgoType;
import finos.traderx.algoengine.model.OrderSide;
import finos.traderx.algoengine.model.ParentOrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AlgoEventStoreReplayTest {

  @Test
  void fullReplayRebuildsEveryParentIncludingAlreadyCompletedOrders() {
    List<AlgoEvent> log = new ArrayList<>();
    log.addAll(completedParent("completed", "child-c"));
    log.addAll(completedParent("second", "child-s"));

    AlgoOrderState restarted = new AlgoOrderState();
    assertEquals(8, AlgoEventStore.replayEvents(log, restarted::apply));

    assertEquals(2, restarted.all().size());
    assertEquals(ParentOrderStatus.COMPLETED, restarted.get("completed").getStatus());
    assertEquals(ParentOrderStatus.COMPLETED, restarted.get("second").getStatus());
    assertTrue(restarted.get("completed").getBuckets().get(0).isFilled());
    assertEquals("child-s", restarted.get("second").getBuckets().get(0).getChildOrderId());
  }

  // ---------------------------------------------------------------------------------------------
  // Recovery classification. `replayed 0` is correct against an empty stream and is ALSO exactly
  // what permanent state loss looks like; the broker runs on non-durable storage by decision, so
  // the second case really happens. These four cases are the ones the single line conflated.
  // ---------------------------------------------------------------------------------------------

  /** replayed, msgCount, lastSequence, appliedBefore, inspectFailure — with no torn log. */
  private static Recovery classify(int replayed, long msgCount, long lastSequence,
      long appliedBefore, String inspectFailure) {
    return classify(replayed, msgCount, lastSequence, appliedBefore, List.of(), inspectFailure);
  }

  private static Recovery classify(int replayed, long msgCount, long lastSequence,
      long appliedBefore, List<String> orphanedParents, String inspectFailure) {
    return AlgoEventStore.classifyRecovery(replayed, msgCount, lastSequence, appliedBefore,
        orphanedParents, inspectFailure);
  }

  @Test
  void anEmptyStreamAndALostLogAreNotTheSameVerdictOrTheSameLine() {
    // Boot against a stream that has never carried a message: nothing was lost BY THIS CONSUMER.
    Recovery empty = classify(0, 0, 0, 0, null);
    // The identical "replayed 0", except this process had already applied 12 events off that
    // stream and the stream now reports none — the log it was rebuilt from is gone.
    Recovery lost = classify(0, 0, 0, 12, null);

    assertEquals(Verdict.STREAM_EMPTY, empty.verdict());
    assertEquals(Verdict.LOG_LOST, lost.verdict());
    assertNotEquals(empty.message(), lost.message());
    // The count is the operator's whole handle on how much went: say it.
    assertTrue(lost.message().contains("12"), lost.message());
    assertFalse(empty.message().contains("12"), empty.message());
  }

  @Test
  void aStreamReportingZeroAfterCarryingMessagesIsLossOnItsOwnEvidence() {
    // Nothing applied by this process (a cold boot), but the stream's own last sequence says it
    // carried 40 messages that are no longer there. Loss, without needing this consumer's memory.
    Recovery r = classify(0, 0, 40, 0, null);
    assertEquals(Verdict.LOG_LOST, r.verdict());
    assertTrue(r.message().contains("40"), r.message());
  }

  @Test
  void aStreamThatStillHoldsTheLogBlamesThisConsumerNotTheBroker() {
    Recovery r = classify(0, 40, 40, 0, null);
    assertEquals(Verdict.CONSUMER_REPLAYED_NONE, r.verdict());
    assertTrue(r.message().contains("40"), r.message());
  }

  @Test
  void anUninspectableStreamIsUndeterminedRatherThanEitherAnswer() {
    Recovery r = classify(0, -1, -1, 7, "JetStreamApiException: stream not found [10059]");
    assertEquals(Verdict.UNDETERMINED, r.verdict());
    // Rule: let the side that knows do the talking — carry the broker's own words through.
    assertTrue(r.message().contains("10059"), r.message());
    assertNotEquals(classify(0, 0, 0, 7, null).message(), r.message());
  }

  @Test
  void onlyAnActualReplayIsQuiet() {
    assertFalse(classify(40, 40, 40, 0, null).alarming());
    assertEquals(Verdict.REPLAYED, classify(40, 40, 40, 0, null).verdict());
    assertTrue(classify(0, 0, 0, 0, null).alarming());
    assertTrue(classify(0, 0, 0, 12, null).alarming());
    assertTrue(classify(0, 40, 40, 0, null).alarming());
    assertTrue(classify(0, -1, -1, 0, "boom").alarming());
  }

  @Test
  void everyVerdictRendersItsOwnLine() {
    List<Recovery> all = List.of(
        classify(40, 40, 40, 0, null),
        classify(0, 0, 0, 0, null),
        classify(0, 0, 0, 12, null),
        classify(0, 40, 40, 0, null),
        classify(0, -1, -1, 0, "boom"),
        classify(40, 40, 40, 0, List.of("orphan-p"), null));
    Set<String> messages = new HashSet<>();
    Set<Verdict> verdicts = new HashSet<>();
    for (Recovery r : all) {
      messages.add(r.message());
      verdicts.add(r.verdict());
    }
    assertEquals(all.size(), verdicts.size(), "two inputs collapsed onto one verdict");
    assertEquals(all.size(), messages.size(), "two verdicts render the same line: " + messages);
  }

  // ---------------------------------------------------------------------------------------------
  // A TORN log — a tail whose ParentOrderCreated events are missing, which is what a broker wipe
  // leaves behind when the engine survives it and keeps appending. The wipe resets the stream's
  // sequencing, so first_seq=1/last_seq=N/messages=N holds for the tail exactly as it does for a
  // complete log: "replayed N of N" is TRUE and tells the operator nothing. Only the applier sees
  // the tear, by being asked for a parent it never created.
  // ---------------------------------------------------------------------------------------------

  @Test
  void aTornLogIsNotAQuietReplayAndNamesTheParentsItNeverRebuilt() {
    AlgoOrderState state = new AlgoOrderState();
    assertEquals(5, AlgoEventStore.replayEvents(tornTail(), state::apply));

    // Everything the tail DID carry is still rebuilt: this is not a failed replay, which is
    // precisely why the arithmetic alone reads healthy.
    assertEquals(1, state.all().size());
    assertNotNull(state.get("survivor"));
    // Three of the five events named two parents with no creation event. DISTINCT parents is what
    // an operator acts on — how many are unowned, not how many messages mentioned them.
    List<String> orphaned = state.orphanedParents().stream().sorted().toList();
    assertEquals(List.of("torn-a", "torn-b"), orphaned);

    Recovery r = classify(5, 5, 5, 0, orphaned, null);
    assertEquals(Verdict.REPLAYED_WITH_ORPHANS, r.verdict());
    assertTrue(r.alarming(), "a torn log logged at INFO is the whole defect: " + r.message());
    assertTrue(r.message().contains("2 parent order(s)"), r.message());
    assertTrue(r.message().contains("torn-a") && r.message().contains("torn-b"), r.message());
    // Say what this side can observe. It cannot see the book, so the children are "may still be".
    assertTrue(r.message().contains("may still be live"), r.message());
  }

  @Test
  void anIntactReplayStaysTheQuietLineItAlreadyWas() {
    AlgoOrderState state = new AlgoOrderState();
    assertEquals(4, AlgoEventStore.replayEvents(completedParent("intact", "child-i"), state::apply));
    assertEquals(Set.of(), state.orphanedParents());

    Recovery r = classify(4, 4, 4, 0, List.copyOf(state.orphanedParents()), null);
    assertEquals(Verdict.REPLAYED, r.verdict());
    assertFalse(r.alarming());
    // Exact, not "does not contain orphan": REPLAYED is the only verdict that logs at INFO, and an
    // operator learns to trust its silence. A clause that leaks into a healthy recovery spends it.
    assertEquals("replayed 4 of 4 algo-engine events from TRADERX_ALGO_ENGINE (last sequence 4)",
        r.message());
  }

  @Test
  void aBucketIndexThatDoesNotExistIsNotATear() {
    AlgoOrderState state = new AlgoOrderState();
    Instant now = Instant.parse("2026-07-15T12:00:00Z");
    state.apply(AlgoEvent.parentOrderCreated("p", 1, "IBM", OrderSide.Buy, 10, AlgoType.TWAP, 10, 10,
        List.of(new AlgoEvent.BucketSeed(0, 0L, 10)), now));
    // The other reason applySubmitted returns early. The PARENT is here — so this is some other
    // defect, and counting it would make the recovery line claim a tear that is not in the log.
    state.apply(AlgoEvent.childOrderSubmitted("p", 9, "c", "p:9", new BigDecimal("1.00"), now));
    assertEquals(Set.of(), state.orphanedParents());
  }

  /** The tail a wipe leaves: one parent created after it (rebuilt fine) and two whose creation
   * events went with the old stream, named by three events between them. */
  private static List<AlgoEvent> tornTail() {
    Instant now = Instant.parse("2026-08-21T20:00:00Z");
    return List.of(
        AlgoEvent.parentOrderCreated("survivor", 22214, "AAPL", OrderSide.Buy, 100, AlgoType.TWAP,
            10, 10, List.of(new AlgoEvent.BucketSeed(0, 0L, 100)), now),
        AlgoEvent.childOrderSubmitted("survivor", 0, "child-s", "survivor:0",
            new BigDecimal("200.00"), now),
        AlgoEvent.childOrderSubmitted("torn-a", 2, "child-a2", "torn-a:2",
            new BigDecimal("200.00"), now),
        AlgoEvent.childOrderFillObserved("torn-a", 2, 0, new BigDecimal("200.00"), now),
        AlgoEvent.childOrderSubmitted("torn-b", 0, "child-b0", "torn-b:0",
            new BigDecimal("200.00"), now));
  }

  private static List<AlgoEvent> completedParent(String parentId, String childId) {
    Instant now = Instant.parse("2026-07-15T12:00:00Z");
    return List.of(
        AlgoEvent.parentOrderCreated(parentId, 1, "IBM", OrderSide.Buy, 10, AlgoType.TWAP,
            10, 10, List.of(new AlgoEvent.BucketSeed(0, 0L, 10)), now),
        AlgoEvent.childOrderSubmitted(parentId, 0, childId, parentId + ":0",
            new BigDecimal("100.00"), now),
        AlgoEvent.childOrderFillObserved(parentId, 0, 0, new BigDecimal("100.00"), now),
        AlgoEvent.parentOrderCompleted(parentId, now));
  }
}
