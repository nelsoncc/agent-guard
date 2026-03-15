package io.agentguard.core;

/**
 * The outcome of a single guard evaluation.
 *
 * <p>Guards return a {@code GuardStatus} to indicate whether execution
 * should proceed, be blocked, or require additional human input before
 * the agent is allowed to continue.
 *
 * <pre>{@code
 * GuardResult result = guard.evaluate(toolCall);
 * switch (result.status()) {
 *     case ALLOWED          -> agent.execute(toolCall);
 *     case BLOCKED          -> throw new ToolDeniedException(result.blockReason());
 *     case REQUIRE_CONSENT  -> consentService.requestApproval(toolCall, result);
 * }
 * }</pre>
 */
public enum GuardStatus {

    /**
     * The agent action is permitted and should proceed normally.
     */
    ALLOWED,

    /**
     * The agent action is denied. The guard has determined that this action
     * violates policy (budget exceeded, tool denied, injection detected, loop
     * detected, etc.). The caller must not execute the action.
     *
     * <p>See {@link GuardResult#blockReason()} and {@link GuardResult#violation()}
     * for details about why the action was blocked.
     */
    BLOCKED,

    /**
     * The agent action requires explicit human approval before it can proceed.
     * Execution must be paused and resumed only after the consent is granted.
     *
     * <p>This status is typically returned for high-risk tools such as
     * {@code send_email}, {@code delete_file}, or any tool marked with
     * {@link io.agentguard.core.policy.ToolRisk#HIGH} or higher.
     */
    REQUIRE_CONSENT;

    /**
     * @return {@code true} if execution should proceed without waiting.
     */
    public boolean isAllowed() {
        return this == ALLOWED;
    }

    /**
     * @return {@code true} if execution must be stopped.
     */
    public boolean isBlocked() {
        return this == BLOCKED;
    }

    /**
     * @return {@code true} if execution must be paused for human approval.
     */
    public boolean requiresConsent() {
        return this == REQUIRE_CONSENT;
    }
}
