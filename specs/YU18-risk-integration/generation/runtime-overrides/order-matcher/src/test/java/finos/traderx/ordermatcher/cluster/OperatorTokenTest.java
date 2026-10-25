package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ENGINE-REVIEW S2: the operator credential pair that now guards halt/open, business day and seed. */
class OperatorTokenTest {
    @Test void tokenAndOperatorBothRequired() {
        assertTrue(ClusterGatewayMain.operatorTokenMatches("s3cret", "ops", "s3cret"));
        assertFalse(ClusterGatewayMain.operatorTokenMatches("wrong", "ops", "s3cret"));
        assertFalse(ClusterGatewayMain.operatorTokenMatches(null, "ops", "s3cret"));
        assertFalse(ClusterGatewayMain.operatorTokenMatches("s3cret", null, "s3cret"));
        assertFalse(ClusterGatewayMain.operatorTokenMatches("s3cret", "  ", "s3cret"));
        assertFalse(ClusterGatewayMain.operatorTokenMatches("s3cre", "ops", "s3cret"), "prefix is not a match");
    }
}
