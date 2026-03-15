package io.agentguard.core;

/**
 * Categorises the reason an agent action was blocked or flagged.
 *
 * <p>Each {@link GuardResult} that has a {@link GuardStatus#BLOCKED} or
 * {@link GuardStatus#REQUIRE_CONSENT} status carries exactly one
 * {@code ViolationType} describing which guard triggered.
 */
public enum ViolationType {

    /**
     * The token or cost budget for this run / hour / day has been exhausted.
     * See Milestone 1 — Budget Firewall.
     */
    BUDGET_EXCEEDED,

    /**
     * The agent called the same tool with the same (or semantically equivalent)
     * arguments too many times within the configured window.
     * See Milestone 2 — Loop Detector.
     */
    LOOP_DETECTED,

    /**
     * The requested tool is on the explicit denylist.
     * See Milestone 3 — Tool Policy Engine.
     */
    TOOL_DENIED,

    /**
     * The requested tool requires explicit human consent before execution.
     * See Milestone 3 — Tool Policy Engine.
     */
    TOOL_REQUIRES_CONSENT,

    /**
     * A prompt injection pattern was detected in the agent's input or in
     * a tool's output that is about to be fed back to the agent.
     * See Milestone 4 — Injection Guard.
     */
    PROMPT_INJECTION,

    /**
     * A potential data-exfiltration pattern was detected in tool arguments
     * (e.g. suspicious external URLs, email addresses).
     * See Milestone 4 — Injection Guard.
     */
    DATA_EXFILTRATION,

    /**
     * An internal error occurred inside the guard itself.
     * The default fail-safe behaviour is to block; this can be changed to
     * ALLOW in dev/test mode via {@link io.agentguard.core.policy.FailSafeMode}.
     */
    INTERNAL_GUARD_ERROR;
}
