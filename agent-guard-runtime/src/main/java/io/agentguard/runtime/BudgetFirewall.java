package io.agentguard.runtime;

import io.agentguard.core.GuardResult;
import io.agentguard.core.ToolCall;
import io.agentguard.core.ViolationType;
import io.agentguard.core.exception.BudgetExceededException;
import io.agentguard.core.policy.BudgetPolicy;
import io.agentguard.core.policy.ExecutionContext;
import io.agentguard.core.spi.Resettable;
import io.agentguard.core.spi.ToolGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime enforcement of token and cost budgets (Milestone 1, Issues #5–#9).
 *
 * <h2>How it works</h2>
 * <p>The firewall holds one {@link PolicyState} per {@link BudgetPolicy}:
 * <ul>
 *   <li><strong>Per-run policies</strong> — per-run counters keyed by {@code runId},
 *       so concurrent runs never corrupt each other's state (#1 fix).</li>
 *   <li><strong>Rolling-window policies</strong> — a timestamped queue shared across
 *       runs (tracks wall-clock time, not agent runs).</li>
 * </ul>
 *
 * <h2>Run isolation (#1 fix)</h2>
 * <p>Per-run counters are stored in a {@link ConcurrentHashMap} keyed by {@code runId}.
 * A single {@code BudgetFirewall} instance can safely serve any number of concurrent
 * agent runs. Call {@link #initRun(String)} at the start of each run and
 * {@link #evictRun(String)} at the end.
 *
 * <h2>Multi-tenant (#2 fix)</h2>
 * <p>{@link #recordUsage(long, long, String, ExecutionContext)} only credits usage to
 * policies whose {@code workspaceId}/{@code userId} scope matches the supplied context,
 * eliminating cross-tenant contamination. The context-free overload is kept for
 * backward compatibility and applies to unscoped policies only.
 *
 * <p>This class is <strong>thread-safe</strong>.
 */
public final class BudgetFirewall implements ToolGuard, Resettable {

    private static final Logger log = LoggerFactory.getLogger(BudgetFirewall.class);

    /**
     * Sentinel run key used when no explicit run management is in place.
     */
    public static final String DEFAULT_RUN = "__default__";

    private final List<PolicyState> states;
    private final Clock clock;

    public BudgetFirewall(List<BudgetPolicy> policies, TokenCostTable costTable) {
        this(policies, costTable, Clock.systemUTC());
    }

    BudgetFirewall(List<BudgetPolicy> policies, TokenCostTable costTable, Clock clock) {
        if (Objects.requireNonNull(policies, "policies").isEmpty()) {
            throw new IllegalArgumentException("At least one BudgetPolicy is required");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.states = policies.stream()
                .map(p -> new PolicyState(p, Objects.requireNonNull(costTable), clock))
                .toList();
        // Initialise default run state for single-run usage
        states.forEach(s -> s.initRun(DEFAULT_RUN));
    }

    // ─── ToolGuard ────────────────────────────────────────────────────────────

    @Override
    public GuardResult evaluate(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall");
        String runId = toolCall.runId().orElse(DEFAULT_RUN);

        for (PolicyState state : states) {
            if (!state.appliesTo(toolCall)) continue;
            GuardResult result = state.checkLimit(runId);
            if (result.wasBlocked()) {
                log.warn("[BudgetFirewall] BLOCKED '{}' (run='{}'): {}",
                        toolCall.toolName(), runId, result.blockReason().orElse("budget exceeded"));
                return result;
            }
        }
        return GuardResult.allowed();
    }

    // ─── Usage recording ─────────────────────────────────────────────────────

    /**
     * Records token consumption for all <em>unscoped</em> policies and for
     * scoped policies that match the given {@code context}.
     *
     * <p>This is the correct overload for multi-tenant scenarios (#2 fix).
     * Usage is credited only to policies whose {@code workspaceId}/{@code userId}
     * scope matches {@code context}.
     *
     * @param context execution context carrying runId, workspaceId, userId; never null
     */
    public void recordUsage(long inputTokens, long outputTokens, String modelId,
                            ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        String runId = context.runId().orElse(DEFAULT_RUN);
        Instant now = clock.instant();

        for (PolicyState state : states) {
            if (!state.appliesToContext(context)) continue;
            state.record(inputTokens, outputTokens, modelId, now, runId);
        }
        log.debug("[BudgetFirewall] Recorded usage (run='{}', ctx={}): in={} out={} model={}",
                runId, context, inputTokens, outputTokens, modelId);
    }

    /**
     * Records token consumption for all unscoped policies against the default run.
     *
     * <p>Backward-compatible overload. For multi-tenant or concurrent-run scenarios,
     * prefer {@link #recordUsage(long, long, String, ExecutionContext)}.
     */
    public void recordUsage(long inputTokens, long outputTokens, String modelId) {
        Instant now = clock.instant();
        for (PolicyState state : states) {
            // Only apply to unscoped policies — scoped ones need context to avoid contamination
            if (!state.isUnscoped()) continue;
            state.record(inputTokens, outputTokens, modelId, now, DEFAULT_RUN);
        }
        log.debug("[BudgetFirewall] Recorded usage (default run): in={} out={} model={}",
                inputTokens, outputTokens, modelId);
    }

    // ─── Resettable (run lifecycle) ───────────────────────────────────────────

    @Override
    public void initRun(String runId) {
        Objects.requireNonNull(runId, "runId");
        states.forEach(s -> s.initRun(runId));
        log.debug("[BudgetFirewall] Per-run counters initialised for run '{}'.", runId);
    }

    @Override
    public void evictRun(String runId) {
        Objects.requireNonNull(runId, "runId");
        states.forEach(s -> s.evictRun(runId));
        log.debug("[BudgetFirewall] Per-run counters evicted for run '{}'.", runId);
    }

    // ─── Accessors ───────────────────────────────────────────────────────────

    /**
     * Current run cost for the default run.
     */
    public BigDecimal currentRunCost() {
        return currentRunCost(DEFAULT_RUN);
    }

    /**
     * Current run cost for a specific run.
     */
    public BigDecimal currentRunCost(String runId) {
        return states.stream()
                .filter(s -> !s.policy.isRollingWindow())
                .map(s -> s.runCost(runId))
                .reduce(BigDecimal.ZERO, BigDecimal::max);
    }

    /**
     * Remaining budget for the default run.
     */
    public BigDecimal remainingRunBudget() {
        return remainingRunBudget(DEFAULT_RUN);
    }

    /**
     * Remaining budget for a specific run.
     */
    public BigDecimal remainingRunBudget(String runId) {
        return states.stream()
                .filter(s -> !s.policy.isRollingWindow())
                .map(s -> s.policy.maxCost().subtract(s.runCost(runId)))
                .min(BigDecimal::compareTo)
                .orElse(BudgetPolicy.UNLIMITED_COST);
    }

    // ─── Inner: per-policy state ──────────────────────────────────────────────

    private static final class PolicyState {

        final BudgetPolicy policy;
        final TokenCostTable costTable;
        final Clock clock;

        /**
         * Per-run counters keyed by runId (#1 fix).
         * Each entry is only accessed while holding the entry's own AtomicLong/AtomicReference,
         * and the map itself is a ConcurrentHashMap — no additional lock needed.
         */
        private final ConcurrentHashMap<String, PerRunCounters> runCounters
                = new ConcurrentHashMap<>();

        // Rolling-window queue — global per policy (tracks wall-clock time, not runs)
        final Deque<TokenUsage> windowQueue = new ArrayDeque<>();

        PolicyState(BudgetPolicy policy, TokenCostTable costTable, Clock clock) {
            this.policy = policy;
            this.costTable = costTable;
            this.clock = clock;
        }

        void initRun(String runId) {
            runCounters.put(runId, new PerRunCounters());
        }

        void evictRun(String runId) {
            runCounters.remove(runId);
        }

        /**
         * Unscoped = no workspaceId and no userId filter.
         */
        boolean isUnscoped() {
            return policy.workspaceId().isEmpty() && policy.userId().isEmpty();
        }

        /**
         * Whether this policy applies to a ToolCall — used during evaluate().
         * Scoped policies require matching context on the call.
         */
        boolean appliesTo(ToolCall toolCall) {
            return appliesToContext(toolCall.context().orElse(null));
        }

        /**
         * Whether this policy applies to the given context — used during recordUsage().
         * A null context matches only unscoped policies.
         */
        boolean appliesToContext(ExecutionContext ctx) {
            if (policy.workspaceId().isPresent()) {
                String required = policy.workspaceId().get();
                String actual = ctx != null ? ctx.workspaceId().orElse(null) : null;
                if (!required.equals(actual)) return false;
            }
            if (policy.userId().isPresent()) {
                String required = policy.userId().get();
                String actual = ctx != null ? ctx.userId().orElse(null) : null;
                if (!required.equals(actual)) return false;
            }
            return true;
        }

        GuardResult checkLimit(String runId) {
            if (policy.isRollingWindow()) return checkRollingWindow();
            return checkRunLimit(runId);
        }

        private GuardResult checkRunLimit(String runId) {
            PerRunCounters counters = runCounters.computeIfAbsent(runId,
                    id -> new PerRunCounters());
            long tokens = counters.tokens.get();
            BigDecimal cost = counters.cost.get();

            if (policy.maxTokens() != BudgetPolicy.UNLIMITED_TOKENS
                    && tokens >= policy.maxTokens()) {
                String reason = String.format(
                        "Token budget exhausted: %d / %d tokens used this run",
                        tokens, policy.maxTokens());
                return blockedResult(cost, policy.maxCost(), tokens, policy.maxTokens(), reason);
            }
            if (cost.compareTo(policy.maxCost()) >= 0) {
                String reason = String.format(
                        "Cost budget exhausted: $%.6f / $%.6f used this run",
                        cost, policy.maxCost());
                return blockedResult(cost, policy.maxCost(), tokens, policy.maxTokens(), reason);
            }
            return GuardResult.allowed();
        }

        private synchronized GuardResult checkRollingWindow() {
            evictExpired();
            long windowTokens = windowQueue.stream().mapToLong(TokenUsage::totalTokens).sum();
            BigDecimal windowCost = windowQueue.stream().map(TokenUsage::cost)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (policy.maxTokens() != BudgetPolicy.UNLIMITED_TOKENS
                    && windowTokens >= policy.maxTokens()) {
                String reason = String.format(
                        "Token budget exhausted: %d / %d tokens in rolling window %s",
                        windowTokens, policy.maxTokens(), policy.window().orElse(null));
                return blockedResult(windowCost, policy.maxCost(), windowTokens,
                        policy.maxTokens(), reason);
            }
            if (windowCost.compareTo(policy.maxCost()) >= 0) {
                String reason = String.format(
                        "Cost budget exhausted: $%.6f / $%.6f in rolling window %s",
                        windowCost, policy.maxCost(), policy.window().orElse(null));
                return blockedResult(windowCost, policy.maxCost(), windowTokens,
                        policy.maxTokens(), reason);
            }
            return GuardResult.allowed();
        }

        void record(long inputTokens, long outputTokens, String modelId, Instant now,
                    String runId) {
            BigDecimal cost = costTable.calculateCost(inputTokens, outputTokens, modelId);

            if (policy.isRollingWindow()) {
                synchronized (this) {
                    windowQueue.addLast(
                            new TokenUsage(inputTokens, outputTokens, modelId, cost, now));
                    evictExpired();
                }
            } else {
                PerRunCounters counters = runCounters.computeIfAbsent(runId,
                        id -> new PerRunCounters());
                counters.tokens.addAndGet(inputTokens + outputTokens);
                counters.cost.updateAndGet(c -> c.add(cost));
            }
        }

        /**
         * Must be called while holding this monitor.
         */
        private void evictExpired() {
            if (!policy.isRollingWindow()) return;
            Instant cutoff = clock.instant().minus(policy.window().orElseThrow());
            while (!windowQueue.isEmpty()
                    && windowQueue.peekFirst().observedAt().isBefore(cutoff)) {
                windowQueue.pollFirst();
            }
        }

        BigDecimal runCost(String runId) {
            PerRunCounters c = runCounters.get(runId);
            return c != null ? c.cost.get() : BigDecimal.ZERO;
        }

        private static GuardResult blockedResult(
                BigDecimal consumed, BigDecimal limit,
                long tokensConsumed, long tokenLimit,
                String reason) {
            GuardResult base = GuardResult.builder(io.agentguard.core.GuardStatus.BLOCKED)
                    .violation(ViolationType.BUDGET_EXCEEDED)
                    .blockReason(reason)
                    .estimatedCost(consumed.doubleValue())
                    .tokensConsumed(tokensConsumed)
                    .build();
            throw new BudgetExceededException(
                    reason, base, consumed, limit, tokensConsumed, tokenLimit);
        }
    }

    /**
     * Immutable-free per-run cost/token accumulators.
     */
    private static final class PerRunCounters {
        final AtomicLong runTokens = new AtomicLong(0);
        final AtomicReference<BigDecimal> cost = new AtomicReference<>(BigDecimal.ZERO);

        // Alias to keep the record() call site readable
        final AtomicLong tokens = runTokens;
    }
}
