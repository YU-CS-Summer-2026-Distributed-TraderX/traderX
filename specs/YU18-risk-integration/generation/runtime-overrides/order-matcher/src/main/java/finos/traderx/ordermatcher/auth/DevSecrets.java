package finos.traderx.ordermatcher.auth;

import java.util.Set;

/**
 * ENGINE-REVIEW S5: refuse to start on a development secret unless the deployment says so.
 *
 * <p>{@code RISK_CONTROL_TOKEN} defaulted to {@code dev-risk-control} in code and in a manifest, and
 * {@code AUTH_JWT_SECRET} to {@code dev-jwt-shared-secret}, with the GKE manifests shipping a
 * committed {@code kind-local-...} literal as the real JWT secret. A process that would guard its
 * operator and admin routes with a value anyone can read from the repository now fails at startup
 * instead, naming the variable. {@code ALLOW_DEV_SECRETS=true} is the explicit opt-in for kind.
 */
public final class DevSecrets {
    private static final Set<String> KNOWN = Set.of(
        "dev-risk-control",
        "dev-jwt-shared-secret",
        "kind-local-jwt-secret-not-a-real-credential",
        "kind-local-dev-token-secret-not-a-real-credential");

    private DevSecrets() {
    }

    public static boolean isDevSecret(final String value) {
        return value == null || value.isBlank() || KNOWN.contains(value);
    }

    /** @throws IllegalStateException when {@code value} is a known development secret and
     *  {@code allowDev} is false; the message names {@code name} so the operator knows what to set. */
    public static void refuse(final String name, final String value, final boolean allowDev) {
        if (!allowDev && isDevSecret(value)) {
            throw new IllegalStateException(name + " is unset or a development default; set a real value"
                + " or ALLOW_DEV_SECRETS=true on a local rig");
        }
    }

    public static boolean allowDevFromEnv() {
        return "true".equalsIgnoreCase(System.getenv("ALLOW_DEV_SECRETS"));
    }
}
