package io.agentguard.core;

import io.agentguard.core.policy.*;
import io.agentguard.core.spi.ConsentHandler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The central Agent Guard interface.
 *
 * <p>Agent Guard is a runtime governance layer that wraps around any AI agent
 * and adds budget control, loop detection, tool policy enforcement, and prompt
 * injection detection — all in a single, composable API.
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * AgentGuard guard = AgentGuard.builder()
 *     .budget(BudgetPolicy.perHour(BigDecimal.valueOf(2.00)))
 *     .loopDetection(LoopPolicy.maxRepeats(3).withinLastNCalls(10).build())
 *     .toolPolicy(ToolPolicy.denyAll()
 *         .allow("web_search")
 *         .allow("read_file")
 *         .requireConsent("send_email")
 *         .build())
 *     .injectionGuard(InjectionGuardPolicy.defaultRules())
 *     .build();
 *
 * // Evaluate a tool call before the agent executes it
 * ToolCall call = ToolCall.of("call-1", "web_search", Map.of("query", "weather Lisbon"));
 * GuardResult result = guard.evaluateToolCall(call);
 *
 * if (result.wasBlocked()) {
 *     throw new RuntimeException("Blocked: " + result.blockReason().orElse("unknown"));
 * }
 * }</pre>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Implementations must be <strong>thread-safe</strong>.</li>
 *   <li>A single {@code AgentGuard} instance may be shared across concurrent agent runs.</li>
 *   <li>Each agent run may optionally be scoped with a {@code runId} for per-run budget tracking.</li>
 *   <li>On internal error, the guard must follow the {@link FailSafeMode} configured at build time.</li>
 * </ul>
 */
public interface AgentGuard {

    /**
     * Evaluates a single tool call against all configured policies.
     *
     * <p>The evaluation order is:
     * <ol>
     *   <li>Injection guard (scans tool arguments)</li>
     *   <li>Budget firewall (checks current spend)</li>
     *   <li>Loop detector (checks call history)</li>
     *   <li>Tool policy engine (allowlist/denylist/consent)</li>
     * </ol>
     *
     * <p>The first BLOCKED or REQUIRE_CONSENT result short-circuits the chain.
     *
     * @param toolCall the tool invocation to evaluate
     * @return a {@link GuardResult} — never null
     */
    GuardResult evaluateToolCall(ToolCall toolCall);

    /**
     * Records that tokens were consumed by a model call.
     * This updates the running budget counters.
     *
     * @param inputTokens  tokens in the prompt
     * @param outputTokens tokens in the completion
     * @param model        the model identifier
     */
    void recordTokenUsage(long inputTokens, long outputTokens, String model);

    /**
     * Records token consumption with execution context for tenant-aware budget accounting.
     *
     * <p>When a guard is shared across multiple tenants (workspaces / users), callers
     * <em>must</em> use this overload so that usage is credited only to the correct
     * tenant's scoped budget policies. Without context, usage from one tenant can
     * consume another tenant's budget.
     *
     * <p>The {@code context} should carry the same {@code runId}, {@code workspaceId},
     * and {@code userId} that were attached to the evaluated {@link ToolCall}.
     *
     * @param inputTokens  tokens in the prompt
     * @param outputTokens tokens in the completion
     * @param model        the model identifier
     * @param context      execution context for scoped budget filtering; must not be null
     */
    default void recordTokenUsage(long inputTokens, long outputTokens, String model,
                                  io.agentguard.core.policy.ExecutionContext context) {
        // Default: fall back to the unscoped version for backward compatibility.
        // Implementations should override this to apply context-aware filtering.
        recordTokenUsage(inputTokens, outputTokens, model);
    }

    /**
     * Records that a tool call was completed (for loop detection bookkeeping).
     *
     * @param toolCall the tool call that was executed
     */
    void recordToolCallCompleted(ToolCall toolCall);

    /**
     * Resets all per-run state (budget counters, loop detection windows).
     * Call this at the start of each new agent run.
     *
     * @param runId a unique identifier for the new run
     */
    void startRun(String runId);

    /**
     * Signals that the given run has completed and releases its per-run state.
     *
     * <p>Calling {@code endRun} is optional but recommended in long-running services
     * where a single {@code AgentGuard} handles many concurrent runs — it prevents
     * unbounded accumulation of per-run state in memory.
     *
     * <p>After this call, {@link #currentRunCost()} and {@link #remainingBudget()}
     * return values for the default (unscoped) run.
     *
     * @param runId the run identifier passed to {@link #startRun(String)}
     */
    default void endRun(String runId) {
        // Default no-op — overridden by DefaultAgentGuard for memory management.
    }

    /**
     * Returns a snapshot of the current accumulated cost for the active run.
     * Returns 0 if no run is in progress.
     */
    BigDecimal currentRunCost();

    /**
     * Returns the remaining budget for the active run.
     * Returns {@link BudgetPolicy#UNLIMITED_COST} if no budget is configured.
     */
    BigDecimal remainingBudget();

    // ─── Builder ─────────────────────────────────────────────────────────────

    /**
     * Entry point for building an {@code AgentGuard} instance.
     *
     * <p>The builder returns a {@link Builder} that accumulates configuration.
     * When {@link Builder#build()} is called, it produces a
     * {@code DefaultAgentGuard} (from {@code agent-guard-runtime}).
     *
     * <p>The builder delegates to a {@link AgentGuardFactory} discovered via
     * {@link java.util.ServiceLoader} so that the core module stays dependency-free.
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@code AgentGuard}.
     *
     * <p>All setters return {@code this} for chaining.
     * Calling {@link #build()} is required to obtain an instance.
     */
    final class Builder {

        private final List<BudgetPolicy> budgets = new ArrayList<>();
        private LoopPolicy loopPolicy = LoopPolicy.defaults();
        private ToolPolicy toolPolicy;
        private InjectionGuardPolicy injectionGuardPolicy;
        private ConsentHandler consentHandler;
        private long consentTimeoutSeconds = 300L;
        private FailSafeMode failSafeMode = FailSafeMode.FAIL_CLOSED;
        private String workspaceId;
        private String userId;

        private Builder() {
        }

        /**
         * Adds a budget constraint.  Multiple budgets can be combined
         * (e.g., per-run + per-hour + per-day).
         */
        public Builder budget(BudgetPolicy budget) {
            this.budgets.add(Objects.requireNonNull(budget, "budget"));
            return this;
        }

        /**
         * Convenience method: per-hour cost budget in USD.
         */
        public Builder budgetPerHour(double usd) {
            return budget(BudgetPolicy.perHour(BigDecimal.valueOf(usd)));
        }

        /**
         * Convenience method: per-run cost budget in USD.
         */
        public Builder budgetPerRun(double usd) {
            return budget(BudgetPolicy.perRun(BigDecimal.valueOf(usd)));
        }

        /**
         * Configures loop detection. Use {@link LoopPolicy#defaults()} if unsure.
         */
        public Builder loopDetection(LoopPolicy loopPolicy) {
            this.loopPolicy = Objects.requireNonNull(loopPolicy, "loopPolicy");
            return this;
        }

        /**
         * Configures tool policy (allowlist/denylist/consent rules).
         */
        public Builder toolPolicy(ToolPolicy toolPolicy) {
            this.toolPolicy = Objects.requireNonNull(toolPolicy, "toolPolicy");
            return this;
        }

        /**
         * Configures prompt injection detection.
         */
        public Builder injectionGuard(InjectionGuardPolicy policy) {
            this.injectionGuardPolicy = Objects.requireNonNull(policy, "injectionGuardPolicy");
            return this;
        }

        /**
         * Provides a custom consent handler for REQUIRE_CONSENT tool calls.
         */
        public Builder consentHandler(ConsentHandler handler) {
            this.consentHandler = Objects.requireNonNull(handler, "consentHandler");
            return this;
        }

        /**
         * Sets how long (seconds) to wait for a human consent decision before
         * failing closed. Default: 300s (5 minutes).
         */
        public Builder consentTimeoutSeconds(long seconds) {
            if (seconds <= 0) throw new IllegalArgumentException("consentTimeoutSeconds must be > 0");
            this.consentTimeoutSeconds = seconds;
            return this;
        }

        /**
         * Sets the fail-safe mode. Default: {@link FailSafeMode#FAIL_CLOSED}.
         * Override to {@link FailSafeMode#FAIL_OPEN} in development only.
         */
        public Builder failSafe(FailSafeMode mode) {
            this.failSafeMode = Objects.requireNonNull(mode, "failSafeMode");
            return this;
        }

        /**
         * @deprecated Multi-tenant run scoping is not yet implemented at the guard level.
         * Use {@link BudgetPolicy.Builder#workspaceId(String)} to scope individual budget
         * policies to a workspace instead. This method will be removed or repurposed in
         * a future release when per-run context propagation is implemented.
         */
        @Deprecated(since = "0.1.0", forRemoval = true)
        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        /**
         * @deprecated Multi-tenant run scoping is not yet implemented at the guard level.
         * Use {@link BudgetPolicy.Builder#userId(String)} to scope individual budget
         * policies to a user instead. This method will be removed or repurposed in
         * a future release when per-run context propagation is implemented.
         */
        @Deprecated(since = "0.1.0", forRemoval = true)
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        // ─── Accessors for runtime module ─────────────────────────────────────

        public List<BudgetPolicy> budgets() {
            return List.copyOf(budgets);
        }

        public LoopPolicy loopPolicy() {
            return loopPolicy;
        }

        public ToolPolicy toolPolicy() {
            return toolPolicy;
        }

        public InjectionGuardPolicy injectionGuardPolicy() {
            return injectionGuardPolicy;
        }

        public ConsentHandler consentHandler() {
            return consentHandler;
        }

        public long consentTimeoutSeconds() {
            return consentTimeoutSeconds;
        }

        public FailSafeMode failSafeMode() {
            return failSafeMode;
        }

        public String workspaceId() {
            return workspaceId;
        }

        public String userId() {
            return userId;
        }

        /**
         * Builds and returns the configured {@code AgentGuard} instance.
         *
         * <p>Requires {@code agent-guard-runtime} on the classpath to provide
         * the factory implementation via {@link java.util.ServiceLoader}.
         *
         * @throws IllegalStateException if no {@link AgentGuardFactory} is found
         */
        public AgentGuard build() {
            java.util.ServiceLoader<AgentGuardFactory> loader =
                    java.util.ServiceLoader.load(AgentGuardFactory.class);
            AgentGuardFactory factory = loader.findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No AgentGuardFactory found on classpath. " +
                                    "Add agent-guard-runtime as a dependency."));
            return factory.create(this);
        }
    }
}
