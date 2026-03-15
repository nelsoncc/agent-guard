package io.agentguard.runtime;

import io.agentguard.core.*;
import io.agentguard.core.exception.BudgetExceededException;
import io.agentguard.core.exception.PromptInjectionException;
import io.agentguard.core.policy.BudgetPolicy;
import io.agentguard.core.policy.ExecutionContext;
import io.agentguard.core.policy.FailSafeMode;
import io.agentguard.core.spi.ConsentHandler;
import io.agentguard.core.spi.Resettable;
import io.agentguard.core.spi.ToolGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default runtime implementation of {@link AgentGuard}.
 *
 * <h2>Run isolation (#1 fix)</h2>
 * <p>Each call to {@link #startRun(String)} allocates isolated per-run state in every
 * {@link Resettable} guard (loop window, budget counters) keyed by {@code runId}.
 * Concurrent runs sharing this instance never corrupt each other's state.
 * Call {@link #endRun(String)} when a run completes to release memory.
 *
 * <h2>Context propagation</h2>
 * <p>Every {@link ToolCall} that enters the chain is enriched with the calling run's
 * {@code runId} via {@link ToolCall#withRunId(String)} if not already set.  This ensures
 * guards can key their per-run state correctly without requiring callers to set it.
 *
 * <h2>Multi-tenant budget (#2 fix)</h2>
 * <p>{@link #recordTokenUsage(long, long, String, ExecutionContext)} forwards the full
 * context to {@link BudgetFirewall#recordUsage(long, long, String, ExecutionContext)},
 * so only policies whose scope matches the context are credited.
 *
 * <p>Thread-safe: all mutable state is isolated per-run in thread-safe structures.
 */
public final class DefaultAgentGuard implements AgentGuard {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentGuard.class);

    private final List<ToolGuard> guardChain;
    private final FailSafeMode failSafeMode;
    private final ConsentHandler consentHandler;
    private final BudgetFirewall budgetFirewall;   // null when no budget configured

    /**
     * Active run IDs — used to resolve currentRunCost / remainingBudget for a caller
     * that has called startRun() without passing a runId to those query methods.
     * We keep a ref-counted set so concurrent runs all appear active.
     */
    private final ConcurrentHashMap<String, Boolean> activeRunIds = new ConcurrentHashMap<>();

    /**
     * The most recently started run ID, used to enrich ToolCalls that carry no runId.
     * Volatile so that sequential startRun → evaluateToolCall patterns always see the
     * correct run without requiring external synchronisation.
     */
    private volatile String mostRecentRunId = null;

    DefaultAgentGuard(
            List<ToolGuard> guardChain,
            FailSafeMode failSafeMode,
            ConsentHandler consentHandler,
            BudgetFirewall budgetFirewall) {
        this.guardChain = List.copyOf(Objects.requireNonNull(guardChain));
        this.failSafeMode = Objects.requireNonNull(failSafeMode);
        this.consentHandler = consentHandler;
        this.budgetFirewall = budgetFirewall;
    }

    // ─── AgentGuard ───────────────────────────────────────────────────────────

    @Override
    public GuardResult evaluateToolCall(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall");

        // Enrich with the most-recently started runId if the call doesn't already carry one.
        // Using mostRecentRunId (not activeRunIds.keys().nextElement()) ensures that after
        // startRun("run-2"), subsequent calls are routed to run-2's state — not run-1's —
        // even when both runs are still active in the map.
        ToolCall enriched = toolCall.runId().isPresent()
                ? toolCall
                : mostRecentRunId == null
                ? toolCall
                : toolCall.withRunId(mostRecentRunId);

        try {
            for (ToolGuard guard : guardChain) {
                GuardResult result = guard.evaluate(enriched);
                if (!result.isAllowed()) {
                    log.debug("[AgentGuard] Tool '{}' blocked by {}: {}",
                            enriched.toolName(), guard.getClass().getSimpleName(),
                            result.blockReason().orElse(""));
                    return result;
                }
            }
            return GuardResult.allowed();

        } catch (BudgetExceededException e) {
            log.warn("[AgentGuard] Budget exceeded for '{}': {}",
                    enriched.toolName(), e.getMessage());
            return e.guardResult();

        } catch (PromptInjectionException e) {
            log.warn("[AgentGuard] Injection detected for '{}': {}",
                    enriched.toolName(), e.getMessage());
            return e.guardResult();

        } catch (Exception e) {
            return handleGuardError(enriched, e);
        }
    }

    /**
     * Records token usage against the default run (backward-compat).
     * Only unscoped budget policies are credited; scoped ones require context.
     */
    @Override
    public void recordTokenUsage(long inputTokens, long outputTokens, String model) {
        if (budgetFirewall != null) {
            budgetFirewall.recordUsage(inputTokens, outputTokens, model);
        }
        log.debug("[AgentGuard] Token usage recorded (no context): in={} out={} model={}",
                inputTokens, outputTokens, model);
    }

    /**
     * Records token usage with full execution context (#2 fix).
     * Usage is credited only to policies whose scope matches {@code context}.
     */
    @Override
    public void recordTokenUsage(long inputTokens, long outputTokens, String model,
                                 ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        if (budgetFirewall != null) {
            budgetFirewall.recordUsage(inputTokens, outputTokens, model, context);
        }
        log.debug("[AgentGuard] Token usage recorded (ctx={}): in={} out={} model={}",
                context, inputTokens, outputTokens, model);
    }

    @Override
    public void recordToolCallCompleted(ToolCall toolCall) {
        log.debug("[AgentGuard] Tool call completed: {}", toolCall.toolName());
    }

    /**
     * Initialises fresh per-run state for all stateful guards and registers the run.
     * Safe to call concurrently for different run IDs.
     */
    @Override
    public void startRun(String runId) {
        Objects.requireNonNull(runId, "runId");
        activeRunIds.put(runId, Boolean.TRUE);
        mostRecentRunId = runId;
        guardChain.forEach(g -> {
            if (g instanceof Resettable r) r.initRun(runId);
        });
        log.debug("[AgentGuard] Run started: {}", runId);
    }

    /**
     * Releases per-run state for all stateful guards and deregisters the run.
     * Call this when an agent run has completed to prevent memory leaks in long-lived services.
     */
    @Override
    public void endRun(String runId) {
        Objects.requireNonNull(runId, "runId");
        activeRunIds.remove(runId);
        guardChain.forEach(g -> {
            if (g instanceof Resettable r) r.evictRun(runId);
        });
        log.debug("[AgentGuard] Run ended, state evicted: {}", runId);
    }

    @Override
    public BigDecimal currentRunCost() {
        if (budgetFirewall == null) return BigDecimal.ZERO;
        String runId = mostRecentRunId != null ? mostRecentRunId : BudgetFirewall.DEFAULT_RUN;
        return budgetFirewall.currentRunCost(runId);
    }

    @Override
    public BigDecimal remainingBudget() {
        if (budgetFirewall == null) return BudgetPolicy.UNLIMITED_COST;
        String runId = mostRecentRunId != null ? mostRecentRunId : BudgetFirewall.DEFAULT_RUN;
        return budgetFirewall.remainingRunBudget(runId);
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private GuardResult handleGuardError(ToolCall toolCall, Exception e) {
        log.error("[AgentGuard] Internal guard error evaluating '{}': {}",
                toolCall.toolName(), e.getMessage(), e);
        if (failSafeMode.isFailClosed()) {
            return GuardResult.blockedTool(
                    toolCall.toolName(),
                    ViolationType.INTERNAL_GUARD_ERROR,
                    "Internal guard error (fail-closed): " + e.getMessage());
        } else {
            log.warn("[AgentGuard] FAIL_OPEN: allowing '{}' despite guard error. " +
                    "DO NOT USE FAIL_OPEN IN PRODUCTION.", toolCall.toolName());
            return GuardResult.allowed();
        }
    }
}
