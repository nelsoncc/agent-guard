package io.agentguard.core.policy;

/**
 * Controls what the guard does when an <em>internal guard error</em> occurs
 * (e.g., the budget datastore is unreachable, a policy rule throws an exception).
 *
 * <p>The principle of least surprise: production systems should use
 * {@link #FAIL_CLOSED} so that a guard malfunction never silently allows
 * dangerous actions. Development/test environments may use {@link #FAIL_OPEN}
 * to avoid blocking runs when observability infrastructure is unavailable.
 */
public enum FailSafeMode {

    /**
     * On internal guard error → <strong>BLOCK</strong> the action.
     * Safe default for production. Prevents unguarded execution if the
     * guard itself is broken.
     */
    FAIL_CLOSED,

    /**
     * On internal guard error → <strong>ALLOW</strong> the action (and log a warning).
     * Useful for local development when OTel/budget backends are not running.
     * <strong>Never use in production.</strong>
     */
    FAIL_OPEN;

    public boolean isFailClosed() {
        return this == FAIL_CLOSED;
    }

    public boolean isFailOpen() {
        return this == FAIL_OPEN;
    }
}
