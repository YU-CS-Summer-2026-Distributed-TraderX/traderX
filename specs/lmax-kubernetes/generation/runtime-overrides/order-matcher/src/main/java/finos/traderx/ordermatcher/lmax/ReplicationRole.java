package finos.traderx.ordermatcher.lmax;

import org.springframework.stereotype.Component;

/**
 * Atomic BLP replication role. Shared between LmaxEngine, output handlers, and controllers.
 * Starts as UNKNOWN until LeaderElection determines PRIMARY or FOLLOWER.
 */
@Component
public final class ReplicationRole {
    public enum Role { UNKNOWN, PRIMARY, FOLLOWER }

    private volatile Role role = Role.UNKNOWN;

    public void set(Role r) {
        this.role = r;
    }

    public Role get() {
        return role;
    }

    public boolean isPrimary() {
        return role == Role.PRIMARY;
    }

    public boolean isFollower() {
        return role == Role.FOLLOWER;
    }

    public boolean isUnknown() {
        return role == Role.UNKNOWN;
    }
}
