package finos.traderx.tradeprocessor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import finos.traderx.tradeprocessor.model.OrderUpdate;
import org.junit.jupiter.api.Test;

/**
 * ADR-093: the booking update now carries {@code clientOrderKey}. It is not economic event identity, so the event digest
 * excludes it. Kept in, every event retained BEFORE the field existed would digest differently (the bean serializes it,
 * as null) from the {@code eventDigest} stored for it, and recovery would refuse that history.
 */
class ClientOrderKeyDigestTest {
  private static final String PRE_CHANGE = "{\"id\":\"7-42\",\"accountId\":11,\"security\":\"IBM\",\"side\":\"Buy\","
      + "\"quantity\":10,\"remainingQuantity\":10,\"limitPrice\":50.0,\"status\":\"NEW\",\"lastFillQuantity\":0,"
      + "\"createdAt\":1,\"updatedAt\":2,\"traceId\":\"0123456789abcdef0123456789abcdef\"}";
  /** OrderProjectionService.digest of PRE_CHANGE, computed by the trade-processor before ADR-093 (1726a13a). */
  private static final String PRE_CHANGE_DIGEST = "37582cbdb072cfedf1f26810c8c80a0261184ad4cbb000b6f586e7e3a56c25b8";
  private final ObjectMapper mapper = new ObjectMapper();

  @Test void anEventRetainedBeforeTheFieldStillVerifies() throws Exception {
    assertEquals(PRE_CHANGE_DIGEST, OrderProjectionService.digest(mapper.readValue(PRE_CHANGE, OrderUpdate.class)));
  }

  @Test void theClientKeyIsNotPartOfTheDigest() throws Exception {
    OrderUpdate keyed = mapper.readValue(PRE_CHANGE.replace("\"status\"", "\"clientOrderKey\":\"18446744073709551615\",\"status\""),
        OrderUpdate.class);
    assertEquals("18446744073709551615", keyed.getClientOrderKey());
    assertEquals(PRE_CHANGE_DIGEST, OrderProjectionService.digest(keyed));
  }

  /** The typed consumer of /orders: a field it does not know yet is ignored, never a parse failure. */
  @Test void anOrderUpdateToleratesAFieldItDoesNotKnow() throws Exception {
    OrderUpdate u = mapper.readValue(PRE_CHANGE.replace("\"status\"", "\"aFieldFromTheFuture\":1,\"status\""), OrderUpdate.class);
    assertEquals("NEW", u.getStatus());
  }
}
