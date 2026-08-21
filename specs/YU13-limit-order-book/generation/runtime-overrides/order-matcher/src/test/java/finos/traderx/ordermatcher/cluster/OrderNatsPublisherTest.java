package finos.traderx.ordermatcher.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The order-lifecycle → NATS payload is the seam every downstream assertion rides on, so its
 * encoding is pinned here: the epoch-qualified id that keeps the read model from colliding across
 * incarnations (brief 05 item 0), the RestingOrder status-ordinal → orderbook-string mapping, and
 * the Px-tick decimal formatting shared with the trade bridge.
 */
class OrderNatsPublisherTest {

  private static String encode(String epoch, OrderNatsPublisher.Rec r) {
    OrderNatsPublisher pub = new OrderNatsPublisher("nats://unused", "/orders", epoch, 16);
    return new String(pub.encode(new StringBuilder(), r), StandardCharsets.UTF_8);
  }

  private static OrderNatsPublisher.Rec rec() {
    OrderNatsPublisher.Rec r = new OrderNatsPublisher.Rec();
    r.orderRef = 42;
    r.accountId = 22214;
    r.security = "AAPL";
    r.side = 0; // buy
    r.quantity = 100;
    r.remainingQty = 100;
    r.limitPx = 150_500_000L; // 150.500000 ticks
    r.status = 0; // NEW
    r.lastExecPx = -1; // Px.NONE
    r.lastFillQty = 0;
    r.createdAtMillis = 1_700_000_000_000L;
    r.updatedAtMillis = 1_700_000_000_500L;
    return r;
  }

  @Test
  void idIsEpochQualifiedSoItCannotCollideAcrossIncarnations() {
    OrderNatsPublisher.Rec r = rec();
    // The whole point of the id: epoch 7 orderRef 42 is a different row from epoch 1 orderRef 42.
    assertTrue(encode("7", r).contains("\"id\":\"7-42\""), "id must be epoch-qualified <epoch>-<ref>");
    assertTrue(encode("1", r).contains("\"id\":\"1-42\""), "same ref under a new epoch is a new id");
  }

  @Test
  void payloadCarriesTheFieldsTheReadModelKeysAndEnumeratesOn() {
    String json = encode("1", rec());
    assertTrue(json.contains("\"type\":\"OrderUpdate\""), json);
    assertTrue(json.contains("\"accountId\":22214"), json);
    assertTrue(json.contains("\"security\":\"AAPL\""), json);
    assertTrue(json.contains("\"side\":\"Buy\""), json);
    assertTrue(json.contains("\"quantity\":100"), json);
    assertTrue(json.contains("\"remainingQuantity\":100"), json);
    assertTrue(json.contains("\"limitPrice\":150.500000"), json);
    assertTrue(json.contains("\"status\":\"NEW\""), json);
  }

  @Test
  void statusOrdinalMapsToTheOrderbookCheckConstraintStrings() {
    // These must match orderbook.status CHECK (NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED)
    // exactly, or every write of that status is silently rejected downstream.
    assertStatus(0, "NEW");
    assertStatus(1, "PARTIALLY_FILLED");
    assertStatus(2, "FILLED");
    assertStatus(3, "CANCELED");
    assertStatus(4, "REJECTED");
  }

  private static void assertStatus(int ordinal, String expected) {
    OrderNatsPublisher.Rec r = rec();
    r.status = (byte) ordinal;
    assertTrue(encode("1", r).contains("\"status\":\"" + expected + "\""),
        "status ordinal " + ordinal + " must encode as " + expected);
  }

  @Test
  void pxNoneRendersAsZeroNotNegative() {
    OrderNatsPublisher.Rec r = rec();
    r.limitPx = -1;
    assertTrue(encode("1", r).contains("\"limitPrice\":0.000000"), "Px.NONE must not leak a negative price");
  }

  /**
   * The trace id is the ONLY carriage of an order's trace identity past the gateway (brief 07): it
   * is derived from the client order id, which stops there, so if this field is not on the wire no
   * later reader — a different browser, a FIX client, the algo engine's parent order — can ever
   * name the trace of an order it did not submit.
   */
  @Test
  void aKeyedRecordCarriesTheSame32HexIdTheSpansWereEmittedUnder() {
    OrderNatsPublisher.Rec r = rec();
    r.traceKey = 0x1234_5678_9ABC_DEF0L;
    String expected = OrderTrace.traceIdHex(r.traceKey);
    assertEquals(32, expected.length(), "a W3C trace id is 32 hex chars");
    // Derived with the SAME helper the span emitter uses, not reimplemented here: a second
    // implementation of the mixing is one constant away from publishing an id that resolves to
    // nothing, which reads as a broken join rather than as a bug.
    assertTrue(encode("1", r).contains("\"traceId\":\"" + expected + "\""), encode("1", r));
  }

  @Test
  void anUnkeyedRecordOmitsTheFieldRatherThanEmittingAnEmptyOrFabricatedId() {
    // key 0 = no client order id, or head sampling said no. Either way there is nothing to name.
    // An empty string would persist as an empty string and render as a link to nowhere; the field
    // must simply be absent, which OrderUpdate reads as null.
    OrderNatsPublisher.Rec r = rec();
    r.traceKey = 0L;
    assertFalse(encode("1", r).contains("traceId"), encode("1", r));
  }

  @Test
  void publishedAndDroppedCountersStartClean() {
    OrderNatsPublisher pub = new OrderNatsPublisher("nats://unused", "/orders", "1", 16);
    assertEquals(0, pub.published());
    assertEquals(0, pub.dropped());
  }
}
