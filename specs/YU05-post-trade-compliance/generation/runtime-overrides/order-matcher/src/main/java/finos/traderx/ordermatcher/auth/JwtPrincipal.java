package finos.traderx.ordermatcher.auth;

import java.util.Set;

/**
 * YU05 (post-trade-compliance, ADR-025, FR-PTC40/41): a validated caller identity — subject +
 * the set of accounts they're entitled to act on, or {@code admin} for cross-account operations
 * (full-history reindex, regulatory reports) that aren't scoped to a single account.
 */
public record JwtPrincipal(String subject, Set<Integer> entitledAccounts, boolean admin) {
    public boolean isEntitledTo(int accountId) {
        return admin || entitledAccounts.contains(accountId);
    }
}
