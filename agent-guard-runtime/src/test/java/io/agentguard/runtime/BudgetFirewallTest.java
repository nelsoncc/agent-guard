package io.agentguard.runtime;

import io.agentguard.core.AgentGuard;
import io.agentguard.core.GuardResult;
import io.agentguard.core.ToolCall;
import io.agentguard.core.ViolationType;
import io.agentguard.core.exception.BudgetExceededException;
import io.agentguard.core.policy.BudgetPolicy;
import io.agentguard.core.policy.ExecutionContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Milestone 1 — Budget Firewall (Issues #5–#10).
 * <p>
 * Structure:
 * TokenCostTableTests  — Issue #6 (token counting) + #7 (cost conversion)
 * PerRunLimitTests     — Issue #5 (cost limit), #8 (kill-switch exception)
 * TokenLimitTests      — Issue #5 (token limit)
 * RollingWindowTests   — Issue #5 (per-hour/day support)
 * MultiPolicyTests     — Issue #5 (multiple simultaneous policies)
 * MultiTenantTests     — Issue #9 (workspace/user scoped budgets)
 * IntegrationTests     — Issue #10 (end-to-end via AgentGuard.builder())
 */
class BudgetFirewallTest {

    private static final TokenCostTable TABLE = TokenCostTable.defaults();
    private static final ToolCall ANY_CALL = ToolCall.of("id-1", "web_search");

    // ─── Issue #6 + #7: TokenCostTable ───────────────────────────────────────

    @Nested
    class TokenCostTableTests {

        @Test
        void gpt4o_cost_calculated_correctly() {
            // gpt-4o: $2.50/MTok in, $10.00/MTok out
            // 1000 in + 500 out = $0.0025 + $0.005 = $0.0075
            BigDecimal cost = TABLE.calculateCost(1000, 500, "gpt-4o");
            assertThat(cost).isEqualByComparingTo("0.0075");
        }

        @Test
        void claude_3_5_sonnet_cost_calculated() {
            // $3.00/MTok in, $15.00/MTok out
            BigDecimal cost = TABLE.calculateCost(2000, 1000, "claude-3-5-sonnet");
            assertThat(cost).isEqualByComparingTo("0.021");
        }

        @Test
        void gemini_1_5_pro_cost_calculated() {
            BigDecimal cost = TABLE.calculateCost(1_000_000, 0, "gemini-1.5-pro");
            assertThat(cost).isEqualByComparingTo("1.25");
        }

        @Test
        void ollama_models_are_free() {
            assertThat(TABLE.calculateCost(10_000, 5_000, "ollama/llama3"))
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(TABLE.calculateCost(10_000, 5_000, "ollama:mistral"))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void unknown_model_returns_zero_cost() {
            BigDecimal cost = TABLE.calculateCost(1000, 500, "my-unknown-model");
            assertThat(cost).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void zero_tokens_produces_zero_cost() {
            assertThat(TABLE.calculateCost(0, 0, "gpt-4o"))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void versioned_model_name_matches_base() {
            // "gpt-4o-2024-11-20" should resolve via the exact key in the table
            assertThat(TABLE.hasModel("gpt-4o-2024-11-20")).isTrue();
        }

        @Test
        void custom_model_can_be_added() {
            TokenCostTable custom = TABLE.withModel("my-model", 5.00, 20.00);
            BigDecimal cost = custom.calculateCost(1_000_000, 1_000_000, "my-model");
            assertThat(cost).isEqualByComparingTo("25.00");
        }

        @Test
        void custom_model_overrides_existing() {
            TokenCostTable custom = TABLE.withModel("gpt-4o", 1.00, 1.00);
            BigDecimal cost = custom.calculateCost(1_000_000, 1_000_000, "gpt-4o");
            assertThat(cost).isEqualByComparingTo("2.00");
        }

        @Test
        void table_is_case_insensitive() {
            BigDecimal lower = TABLE.calculateCost(1000, 500, "gpt-4o");
            BigDecimal upper = TABLE.calculateCost(1000, 500, "GPT-4O");
            assertThat(lower).isEqualByComparingTo(upper);
        }
    }

    // ─── Issue #5 + #8: per-run cost limit with kill-switch ──────────────────

    @Nested
    class PerRunLimitTests {

        @Test
        void allows_calls_under_budget() {
            BudgetFirewall fw = firewall(BudgetPolicy.perRun(BigDecimal.valueOf(1.00)));
            assertThat(fw.evaluate(ANY_CALL).isAllowed()).isTrue();
        }

        @Test
        void blocks_when_budget_already_exhausted() {
            BudgetFirewall fw = firewall(BudgetPolicy.perRun(BigDecimal.valueOf(0.001)));
            // Record usage that exceeds the limit
            fw.recordUsage(1_000_000, 0, "gpt-4o");  // $2.50 > $0.001
            assertThatThrownBy(() -> fw.evaluate(ANY_CALL))
                    .isInstanceOf(BudgetExceededException.class);
        }

        @Test
        void budget_exceeded_exception_carries_details() {
            BudgetFirewall fw = firewall(BudgetPolicy.perRun(BigDecimal.valueOf(0.001)));
            fw.recordUsage(1_000_000, 0, "gpt-4o");
            try {
                fw.evaluate(ANY_CALL);
                fail("Expected BudgetExceededException");
            } catch (BudgetExceededException ex) {
                assertThat(ex.consumed()).isGreaterThan(BigDecimal.ZERO);
                assertThat(ex.limit()).isEqualByComparingTo("0.001");
                assertThat(ex.guardResult().violation()).contains(ViolationType.BUDGET_EXCEEDED);
            }
        }

        @Test
        void budget_not_exceeded_at_exactly_the_limit() {
            // Record exactly the limit — next call should still trigger (>=)
            BudgetFirewall fw = firewall(BudgetPolicy.perRun(BigDecimal.valueOf(0.0025)));
            fw.recordUsage(1000, 0, "gpt-4o");  // exactly $0.0025
            assertThatThrownBy(() -> fw.evaluate(ANY_CALL))
                    .isInstanceOf(BudgetExceededException.class);
        }

        @Test
        void reset_clears_per_run_counters() {
            BudgetFirewall fw = firewall(BudgetPolicy.perRun(BigDecimal.valueOf(0.001)));
            fw.recordUsage(1_000_000, 0, "gpt-4o");
            fw.reset();
            // After reset the counter is cleared — evaluate should pass
            assertThat(fw.evaluate(ANY_CALL).isAllowed()).isTrue();
        }

        @Test
        void current_run_cost_reflects_recorded_usage() {
            BudgetFirewall fw = firewall(BudgetPolicy.perRun(BigDecimal.valueOf(10.00)));
            fw.recordUsage(1000, 500, "gpt-4o");  // $0.0075
            assertThat(fw.currentRunCost()).isGreaterThan(BigDecimal.ZERO);
        }

        @Test
        void remaining_budget_decreases_with_usage() {
            BudgetFirewall fw = firewall(BudgetPolicy.perRun(BigDecimal.valueOf(1.00)));
            BigDecimal before = fw.remainingRunBudget();
            fw.recordUsage(100_000, 0, "gpt-4o");
            BigDecimal after = fw.remainingRunBudget();
            assertThat(after).isLessThan(before);
        }
    }

    // ─── Issue #5: token-based limits ────────────────────────────────────────

    @Nested
    class TokenLimitTests {

        @Test
        void blocks_when_token_limit_exceeded() {
            BudgetFirewall fw = firewall(BudgetPolicy.perRunTokens(1000));
            fw.recordUsage(600, 500, "gpt-4o");  // 1100 total > 1000
            assertThatThrownBy(() -> fw.evaluate(ANY_CALL))
                    .isInstanceOf(BudgetExceededException.class)
                    .satisfies(e -> {
                        BudgetExceededException be = (BudgetExceededException) e;
                        assertThat(be.tokensConsumed()).isGreaterThanOrEqualTo(1000L);
                        assertThat(be.tokenLimit()).isEqualTo(1000L);
                    });
        }

        @Test
        void allows_when_token_usage_below_limit() {
            BudgetFirewall fw = firewall(BudgetPolicy.perRunTokens(5000));
            fw.recordUsage(100, 100, "gpt-4o");
            assertThat(fw.evaluate(ANY_CALL).isAllowed()).isTrue();
        }
    }

    // ─── Issue #5: rolling window (per-hour/day) ─────────────────────────────

    @Nested
    class RollingWindowTests {

        @Test
        void rolling_window_accumulates_within_window() {
            // Fixed clock — all observations are "now"
            Clock fixedClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
            BudgetFirewall fw = new BudgetFirewall(
                    List.of(BudgetPolicy.perHour(BigDecimal.valueOf(0.001))),
                    TABLE, fixedClock);

            fw.recordUsage(1_000_000, 0, "gpt-4o");  // $2.50 >> $0.001
            assertThatThrownBy(() -> fw.evaluate(ANY_CALL))
                    .isInstanceOf(BudgetExceededException.class);
        }

        @Test
        void expired_observations_are_evicted_outside_window() {
            // Mutable clock: start 2 hours ago, then advance to now
            java.util.concurrent.atomic.AtomicReference<Instant> currentTime =
                    new java.util.concurrent.atomic.AtomicReference<>(
                            Instant.now().minus(Duration.ofHours(2)));
            Clock mutableClock = new Clock() {
                public java.time.ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                public Clock withZone(java.time.ZoneId z) {
                    return this;
                }

                public Instant instant() {
                    return currentTime.get();
                }
            };

            BudgetFirewall fw = new BudgetFirewall(
                    List.of(BudgetPolicy.perHour(BigDecimal.valueOf(0.001))),
                    TABLE, mutableClock);

            // Record at t=-2h (stamps the observation 2 hours ago)
            fw.recordUsage(1_000_000, 0, "gpt-4o");

            // Advance clock to now — observations are 2h old, outside the 1h window
            currentTime.set(Instant.now());

            // evictExpired() now uses clock.instant() (which is "now"),
            // so cutoff = now - 1h, and the observation at -2h is evicted
            assertThat(fw.evaluate(ANY_CALL).isAllowed()).isTrue();
        }

        @Test
        void rolling_window_does_not_reset_on_startRun() {
            Clock fixedClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
            BudgetFirewall fw = new BudgetFirewall(
                    List.of(BudgetPolicy.perHour(BigDecimal.valueOf(0.001))),
                    TABLE, fixedClock);
            fw.recordUsage(1_000_000, 0, "gpt-4o");
            fw.reset();  // per-run reset — rolling window should persist
            assertThatThrownBy(() -> fw.evaluate(ANY_CALL))
                    .isInstanceOf(BudgetExceededException.class);
        }

        @Test
        void per_day_policy_enforced() {
            Clock fixedClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
            BudgetFirewall fw = new BudgetFirewall(
                    List.of(BudgetPolicy.perDay(BigDecimal.valueOf(0.001))),
                    TABLE, fixedClock);
            fw.recordUsage(1_000_000, 0, "gpt-4o");
            assertThatThrownBy(() -> fw.evaluate(ANY_CALL))
                    .isInstanceOf(BudgetExceededException.class);
        }
    }

    // ─── Issue #5: multiple simultaneous policies ─────────────────────────────

    @Nested
    class MultiPolicyTests {

        @Test
        void most_restrictive_policy_fires_first() {
            // $0.01/run AND $1.00/hour — the run limit is tighter
            BudgetFirewall fw = new BudgetFirewall(List.of(
                    BudgetPolicy.perRun(BigDecimal.valueOf(0.01)),
                    BudgetPolicy.perHour(BigDecimal.valueOf(1.00))
            ), TABLE);
            fw.recordUsage(10_000_000, 0, "gpt-4o");  // $25 >> both limits
            try {
                fw.evaluate(ANY_CALL);
                fail("Expected BudgetExceededException");
            } catch (BudgetExceededException ex) {
                assertThat(ex.getMessage()).containsIgnoringCase("budget");
            }
        }

        @Test
        void each_policy_type_independently_enforced() {
            // Token limit very tight, cost limit generous
            BudgetFirewall fw = new BudgetFirewall(List.of(
                    BudgetPolicy.perRunTokens(10),
                    BudgetPolicy.perRun(BigDecimal.valueOf(100.00))
            ), TABLE);
            fw.recordUsage(5, 10, "gpt-4o");  // 15 tokens > 10 limit
            assertThatThrownBy(() -> fw.evaluate(ANY_CALL))
                    .isInstanceOf(BudgetExceededException.class)
                    .satisfies(e -> assertThat(((BudgetExceededException) e).tokenLimit()).isEqualTo(10L));
        }
    }

    // ─── Issue #9: multi-tenant workspace/user scoped budgets ─────────────────

    @Nested
    class MultiTenantTests {

        @Test
        void workspace_scoped_policy_only_applies_to_matching_workspace() {
            BudgetFirewall fw = new BudgetFirewall(List.of(
                    BudgetPolicy.builder()
                            .maxCost(BigDecimal.valueOf(0.001))
                            .workspaceId("ws-danger")
                            .build()
            ), TABLE);

            fw.recordUsage(1_000_000, 0, "gpt-4o",
                    ExecutionContext.builder().workspaceId("ws-danger").build());  // exceeds limit

            // Call from ws-danger → blocked
            ToolCall dangerCall = ToolCall.builder("c1", "tool")
                    .context(ExecutionContext.builder().workspaceId("ws-danger").build())
                    .build();
            assertThatThrownBy(() -> fw.evaluate(dangerCall))
                    .isInstanceOf(BudgetExceededException.class);

            // Call from ws-safe → policy does not apply → allowed
            ToolCall safeCall = ToolCall.builder("c2", "tool")
                    .context(ExecutionContext.builder().workspaceId("ws-safe").build())
                    .build();
            assertThat(fw.evaluate(safeCall).isAllowed()).isTrue();
        }

        @Test
        void user_scoped_policy_only_applies_to_matching_user() {
            BudgetFirewall fw = new BudgetFirewall(List.of(
                    BudgetPolicy.builder()
                            .maxCost(BigDecimal.valueOf(0.001))
                            .userId("user-cheap")
                            .build()
            ), TABLE);
            fw.recordUsage(1_000_000, 0, "gpt-4o",
                    ExecutionContext.builder().userId("user-cheap").build());

            ToolCall cheapCall = ToolCall.builder("c1", "tool")
                    .context(ExecutionContext.builder().userId("user-cheap").build())
                    .build();
            assertThatThrownBy(() -> fw.evaluate(cheapCall))
                    .isInstanceOf(BudgetExceededException.class);

            ToolCall premiumCall = ToolCall.builder("c2", "tool")
                    .context(ExecutionContext.builder().userId("user-premium").build())
                    .build();
            assertThat(fw.evaluate(premiumCall).isAllowed()).isTrue();
        }

        @Test
        void unscoped_policy_applies_to_all_calls() {
            BudgetFirewall fw = firewall(BudgetPolicy.perRun(BigDecimal.valueOf(0.001)));
            fw.recordUsage(1_000_000, 0, "gpt-4o");

            // Call with any context — unscoped policy applies to everything
            ToolCall withCtx = ToolCall.builder("c1", "tool")
                    .context(ExecutionContext.builder().workspaceId("any-ws").build())
                    .build();
            assertThatThrownBy(() -> fw.evaluate(withCtx))
                    .isInstanceOf(BudgetExceededException.class);
        }

        @Test
        void call_without_context_skips_scoped_policies() {
            // Scoped policy — call has no context → policy does not apply
            BudgetFirewall fw = new BudgetFirewall(List.of(
                    BudgetPolicy.builder()
                            .maxCost(BigDecimal.valueOf(0.001))
                            .workspaceId("ws-x")
                            .build()
            ), TABLE);
            fw.recordUsage(1_000_000, 0, "gpt-4o");
            // ANY_CALL has no context → scoped policy is skipped
            assertThat(fw.evaluate(ANY_CALL).isAllowed()).isTrue();
        }
    }

    // ─── Issue #10: end-to-end via AgentGuard.builder() ───────────────────────

    @Nested
    class IntegrationTests {

        @Test
        void budget_wired_through_agent_guard_builder() {
            var guard = AgentGuard.builder()
                    .budget(BudgetPolicy.perRun(BigDecimal.valueOf(10.00)))
                    .build();

            assertThat(guard.evaluateToolCall(ANY_CALL).isAllowed()).isTrue();
            assertThat(guard.remainingBudget()).isLessThanOrEqualTo(BigDecimal.valueOf(10.00));
        }

        @Test
        void budget_exceeded_blocks_via_agent_guard() {
            var guard = AgentGuard.builder()
                    .budget(BudgetPolicy.perRun(BigDecimal.valueOf(0.001)))
                    .build();

            guard.recordTokenUsage(1_000_000, 0, "gpt-4o");  // $2.50 > $0.001

            GuardResult result = guard.evaluateToolCall(ANY_CALL);
            assertThat(result.wasBlocked()).isTrue();
            assertThat(result.violation()).contains(ViolationType.BUDGET_EXCEEDED);
        }

        @Test
        void current_run_cost_tracks_via_agent_guard() {
            var guard = AgentGuard.builder()
                    .budget(BudgetPolicy.perRun(BigDecimal.valueOf(10.00)))
                    .build();

            guard.recordTokenUsage(1000, 500, "gpt-4o");
            assertThat(guard.currentRunCost()).isGreaterThan(BigDecimal.ZERO);
        }

        @Test
        void start_run_resets_per_run_budget() {
            var guard = AgentGuard.builder()
                    .budget(BudgetPolicy.perRun(BigDecimal.valueOf(0.001)))
                    .build();

            guard.recordTokenUsage(1_000_000, 0, "gpt-4o");
            guard.startRun("run-002");
            // After reset, budget counter is cleared
            assertThat(guard.currentRunCost()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(guard.evaluateToolCall(ANY_CALL).isAllowed()).isTrue();
        }

        @Test
        void no_budget_configured_gives_unlimited() {
            var guard = AgentGuard.builder().build();
            assertThat(guard.remainingBudget()).isEqualByComparingTo(BudgetPolicy.UNLIMITED_COST);
            assertThat(guard.currentRunCost()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void multiple_budgets_all_enforced() {
            var guard = AgentGuard.builder()
                    .budgetPerRun(0.001)
                    .budgetPerHour(1.00)
                    .build();

            guard.recordTokenUsage(1_000_000, 0, "gpt-4o");
            GuardResult result = guard.evaluateToolCall(ANY_CALL);
            assertThat(result.wasBlocked()).isTrue();
            assertThat(result.violation()).contains(ViolationType.BUDGET_EXCEEDED);
        }
    }

    // ─── #1 fix — Concurrent run isolation ───────────────────────────────────

    @Nested
    class ConcurrentRunIsolation {

        @Test
        void two_runs_have_independent_per_run_budgets() {
            var guard = AgentGuard.builder()
                    .budget(BudgetPolicy.perRun(BigDecimal.valueOf(0.005)))
                    .build();

            guard.startRun("run-A");
            // Attach run-A context to usage recording
            var ctxA = ExecutionContext.builder().runId("run-A").build();
            guard.recordTokenUsage(1_000_000, 0, "gpt-4o", ctxA); // $2.50 — exhausts run-A

            guard.startRun("run-B");
            // run-B's own budget is pristine
            var ctxB = ExecutionContext.builder().runId("run-B").build();
            assertThat(guard.currentRunCost()).isEqualByComparingTo(BigDecimal.ZERO);

            // Evaluate with run-B context → should be allowed
            ToolCall callB = ToolCall.builder("c-B", "search")
                    .context(ctxB).build();
            assertThat(guard.evaluateToolCall(callB).isAllowed()).isTrue();

            // Evaluate with run-A context → should be blocked
            ToolCall callA = ToolCall.builder("c-A", "search")
                    .context(ExecutionContext.builder().runId("run-A").build()).build();
            assertThat(guard.evaluateToolCall(callA).wasBlocked()).isTrue();
        }

        @Test
        void endRun_releases_per_run_state() {
            BudgetFirewall fw = new BudgetFirewall(
                    List.of(BudgetPolicy.perRun(BigDecimal.valueOf(0.001))), TABLE);

            fw.initRun("temp-run");
            var ctx = ExecutionContext.builder().runId("temp-run").build();
            fw.recordUsage(1_000_000, 0, "gpt-4o", ctx);
            assertThat(fw.currentRunCost("temp-run"))
                    .isGreaterThan(BigDecimal.valueOf(0.001));

            fw.evictRun("temp-run");
            // After eviction, cost is zero (new state would be allocated lazily)
            assertThat(fw.currentRunCost("temp-run")).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void concurrent_runs_do_not_cross_contaminate_budget() throws Exception {
            // Each run has a generous budget; no run should be blocked
            var guard = AgentGuard.builder()
                    .budget(BudgetPolicy.perRun(BigDecimal.valueOf(1.00)))
                    .build();

            int numRuns = 6;
            var latch = new java.util.concurrent.CountDownLatch(numRuns);
            var blocked = new java.util.concurrent.atomic.AtomicInteger(0);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(numRuns);

            for (int i = 0; i < numRuns; i++) {
                final String runId = "par-run-" + i;
                guard.startRun(runId);
                pool.submit(() -> {
                    try {
                        var ctx = ExecutionContext.builder().runId(runId).build();
                        // Each run spends a small amount — well under its own $1 budget
                        guard.recordTokenUsage(100, 50, "gpt-4o", ctx);
                        ToolCall tc = ToolCall.builder("c-" + runId, "tool")
                                .context(ctx).build();
                        if (guard.evaluateToolCall(tc).wasBlocked()) {
                            blocked.incrementAndGet();
                        }
                    } finally {
                        guard.endRun(runId);
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            pool.shutdown();
            assertThat(blocked.get()).isZero();
        }
    }

    // ─── #2 fix — Cross-tenant budget isolation ───────────────────────────────

    @Nested
    class CrossTenantIsolation {

        @Test
        void usage_from_one_tenant_does_not_burn_another_tenants_scoped_budget() {
            // Two scoped budget policies: one per workspace
            var freePolicy = BudgetPolicy.builder()
                    .maxCost(BigDecimal.valueOf(0.001))
                    .workspaceId("ws-free")
                    .build();
            var proPolicy = BudgetPolicy.builder()
                    .maxCost(BigDecimal.valueOf(10.00))
                    .workspaceId("ws-pro")
                    .build();

            BudgetFirewall fw = new BudgetFirewall(List.of(freePolicy, proPolicy), TABLE);
            fw.initRun(BudgetFirewall.DEFAULT_RUN);

            var freeCtx = ExecutionContext.builder().workspaceId("ws-free").build();
            var proCtx = ExecutionContext.builder().workspaceId("ws-pro").build();

            // Free tenant blows its own budget
            fw.recordUsage(1_000_000, 0, "gpt-4o", freeCtx);

            // Pro tenant should be completely unaffected
            ToolCall proCall = ToolCall.builder("c-pro", "search")
                    .context(proCtx).build();
            assertThat(fw.evaluate(proCall).isAllowed()).isTrue();

            // Free tenant is now blocked
            ToolCall freeCall = ToolCall.builder("c-free", "search")
                    .context(freeCtx).build();
            assertThatThrownBy(() -> fw.evaluate(freeCall))
                    .isInstanceOf(BudgetExceededException.class);
        }

        @Test
        void unscoped_recordUsage_does_not_affect_scoped_policy() {
            // A scoped policy should not be contaminated by the old 3-arg recordUsage
            var scopedPolicy = BudgetPolicy.builder()
                    .maxCost(BigDecimal.valueOf(0.001))
                    .workspaceId("isolated-ws")
                    .build();

            BudgetFirewall fw = new BudgetFirewall(List.of(scopedPolicy), TABLE);
            fw.initRun(BudgetFirewall.DEFAULT_RUN);

            // Old 3-arg call — no context → must NOT affect the scoped policy
            fw.recordUsage(1_000_000, 0, "gpt-4o");

            // Scoped tenant should still be within budget
            ToolCall call = ToolCall.builder("c1", "search")
                    .context(ExecutionContext.builder().workspaceId("isolated-ws").build())
                    .build();
            assertThat(fw.evaluate(call).isAllowed()).isTrue();
        }

        @Test
        void context_aware_recordUsage_credits_only_matching_policy() {
            var ws1Policy = BudgetPolicy.builder()
                    .maxCost(BigDecimal.valueOf(0.001))
                    .workspaceId("ws-1")
                    .build();
            var ws2Policy = BudgetPolicy.builder()
                    .maxCost(BigDecimal.valueOf(0.001))
                    .workspaceId("ws-2")
                    .build();

            BudgetFirewall fw = new BudgetFirewall(List.of(ws1Policy, ws2Policy), TABLE);
            fw.initRun(BudgetFirewall.DEFAULT_RUN);

            // Record usage for ws-1 only
            fw.recordUsage(1_000_000, 0, "gpt-4o",
                    ExecutionContext.builder().workspaceId("ws-1").build());

            // ws-1 is exhausted
            assertThatThrownBy(() -> fw.evaluate(ToolCall.builder("c1", "t")
                            .context(ExecutionContext.builder().workspaceId("ws-1").build()).build()))
                    .isInstanceOf(BudgetExceededException.class);

            // ws-2 is untouched
            assertThat(fw.evaluate(ToolCall.builder("c2", "t")
                            .context(ExecutionContext.builder().workspaceId("ws-2").build()).build())
                    .isAllowed()).isTrue();
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static BudgetFirewall firewall(BudgetPolicy policy) {
        return new BudgetFirewall(List.of(policy), TABLE);
    }
}
