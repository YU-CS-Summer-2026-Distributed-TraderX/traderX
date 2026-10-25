package finos.traderx.ordermatcher.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ENGINE-REVIEW S3: FIX logon credentials and per-CompID account binding. */
class FixSessionAuthTest {
    final FixSessionAuth auth = FixSessionAuth.fromEnv("CLIENT1:pw-one:11|12, CLIENT2:pw-two");

    @Test void logonNeedsTheCompIdsPassword() {
        assertTrue(auth.configured());
        assertTrue(auth.logonAllowed("CLIENT1", "pw-one"));
        assertFalse(auth.logonAllowed("CLIENT1", "pw-two"), "another session's password");
        assertFalse(auth.logonAllowed("CLIENT1", null), "no Password(554)");
        assertFalse(auth.logonAllowed("CLIENT9", "pw-one"), "unknown CompID");
    }

    @Test void accountIsBoundToTheSession() {
        assertTrue(auth.accountAllowed("CLIENT1", 11, 99));
        assertTrue(auth.accountAllowed("CLIENT1", 12, 99));
        assertFalse(auth.accountAllowed("CLIENT1", 13, 99), "Account(1) outside the list");
        assertTrue(auth.accountAllowed("CLIENT2", 99, 99), "no list: the default account only");
        assertFalse(auth.accountAllowed("CLIENT2", 11, 99));
        assertFalse(auth.accountAllowed("CLIENT9", 99, 99));
    }

    @Test void unconfiguredKeepsLegacyBehaviour() {
        final FixSessionAuth none = FixSessionAuth.fromEnv("");
        assertFalse(none.configured());
        assertTrue(none.logonAllowed("ANY", null));
        assertTrue(none.accountAllowed("ANY", 7, 11));
        assertThrows(IllegalArgumentException.class, () -> FixSessionAuth.fromEnv("CLIENT1"), "no password");
    }
}
