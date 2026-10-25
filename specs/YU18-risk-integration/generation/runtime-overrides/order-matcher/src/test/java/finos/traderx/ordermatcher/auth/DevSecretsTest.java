package finos.traderx.ordermatcher.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ENGINE-REVIEW S5: a process refuses a development secret unless the rig opts in. */
class DevSecretsTest {
    @Test void knownDevValuesAndBlanksAreRefused() {
        for (String v : new String[] { null, "", "dev-risk-control", "dev-jwt-shared-secret",
            "kind-local-jwt-secret-not-a-real-credential" }) {
            assertTrue(DevSecrets.isDevSecret(v), String.valueOf(v));
            final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> DevSecrets.refuse("AUTH_JWT_SECRET", v, false));
            assertTrue(ex.getMessage().startsWith("AUTH_JWT_SECRET"), "names the variable");
        }
    }

    @Test void realValuePassesAndOptInAllowsDev() {
        assertDoesNotThrow(() -> DevSecrets.refuse("RISK_CONTROL_TOKEN", "c0rr3ct-h0rse-battery", false));
        assertDoesNotThrow(() -> DevSecrets.refuse("RISK_CONTROL_TOKEN", "dev-risk-control", true));
    }
}
