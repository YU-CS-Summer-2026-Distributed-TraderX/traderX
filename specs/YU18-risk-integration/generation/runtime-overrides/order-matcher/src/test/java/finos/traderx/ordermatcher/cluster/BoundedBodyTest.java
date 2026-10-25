package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** ENGINE-REVIEW S6: a request body is read up to the cap and refused one byte past it. */
class BoundedBodyTest {
    @Test void withinTheCapReadsEverything() throws Exception {
        final byte[] body = "{\"orderRef\":1}".getBytes();
        assertArrayEquals(body, ClusterGatewayMain.readBounded(new ByteArrayInputStream(body), body.length));
        assertArrayEquals(body, ClusterGatewayMain.readBounded(new ByteArrayInputStream(body), 1 << 20));
    }

    @Test void oneBytePastTheCapIsRefusedWithoutBufferingMore() {
        final byte[] body = new byte[4097];
        assertThrows(ClusterGatewayMain.PayloadTooLarge.class,
            () -> ClusterGatewayMain.readBounded(new ByteArrayInputStream(body), 4096));
    }
}
