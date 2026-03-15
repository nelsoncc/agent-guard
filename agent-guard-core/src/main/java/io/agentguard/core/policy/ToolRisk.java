package io.agentguard.core.policy;

/**
 * Risk classification for a tool.
 *
 * <p>The risk level drives the default guard behaviour when no explicit
 * allow/deny/consent rule is configured for a tool:
 *
 * <table border="1">
 *   <tr><th>Risk</th><th>Default action</th></tr>
 *   <tr><td>LOW</td><td>ALLOW</td></tr>
 *   <tr><td>MEDIUM</td><td>ALLOW (logged)</td></tr>
 *   <tr><td>HIGH</td><td>REQUIRE_CONSENT</td></tr>
 *   <tr><td>CRITICAL</td><td>BLOCK</td></tr>
 * </table>
 */
public enum ToolRisk {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /**
     * @return {@code true} if this risk level is at least as severe as {@code other}.
     */
    public boolean isAtLeast(ToolRisk other) {
        return this.ordinal() >= other.ordinal();
    }
}
