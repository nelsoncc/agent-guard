package io.agentguard.runtime;

import io.agentguard.core.*;
import io.agentguard.core.policy.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration smoke tests: verifies that the full wiring via ServiceLoader
 * and the DefaultAgentGuard chain works end-to-end.
 *
 * <p>As subsequent milestones are implemented, these tests will be expanded
 * to cover the real guard logic. For now they validate the scaffold.
 */
class DefaultAgentGuardTest {

    private AgentGuard guard;

    @BeforeEach
    void setUp() {
        // This exercises the full AgentGuard.builder() → ServiceLoader → DefaultAgentGuard path.
        guard = AgentGuard.builder()
                .budget(BudgetPolicy.perRun(BigDecimal.valueOf(0.10)))
                .loopDetection(LoopPolicy.defaults())
                .toolPolicy(ToolPolicy.denyAll()
                        .allow("web_search")
                        .allow("read_file")
                        .requireConsent("send_email")
                        .deny("delete_db")
                        .build())
                .injectionGuard(InjectionGuardPolicy.defaultRules())
                .build();
    }

    @Test
    void service_loader_finds_default_factory() {
        assertThat(guard).isNotNull().isInstanceOf(DefaultAgentGuard.class);
    }

    @Test
    void allowed_tool_passes_scaffold_guard() {
        ToolCall call = ToolCall.of("call-1", "web_search", Map.of("query", "test"));
        // Guard chain is empty in scaffold — all calls pass through
        GuardResult result = guard.evaluateToolCall(call);
        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    void start_run_resets_cost() {
        guard.startRun("run-001");
        assertThat(guard.currentRunCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void remaining_budget_returns_configured_limit() {
        assertThat(guard.remainingBudget()).isEqualByComparingTo(BigDecimal.valueOf(0.10));
    }

    @Test
    void record_token_usage_does_not_throw() {
        assertThatCode(() -> guard.recordTokenUsage(100, 200, "gpt-4o"))
                .doesNotThrowAnyException();
    }

    @Test
    void record_tool_call_completed_does_not_throw() {
        ToolCall call = ToolCall.of("call-2", "read_file");
        assertThatCode(() -> guard.recordToolCallCompleted(call))
                .doesNotThrowAnyException();
    }

    @Test
    void fail_closed_guard_blocks_on_internal_error() {
        // Build a guard whose single ToolGuard always throws
        DefaultAgentGuard failingGuard = new DefaultAgentGuard(
                java.util.List.of(tc -> {
                    throw new RuntimeException("Simulated failure");
                }),
                FailSafeMode.FAIL_CLOSED,
                null,
                null);

        ToolCall call = ToolCall.of("c-err", "some_tool");
        GuardResult result = failingGuard.evaluateToolCall(call);

        assertThat(result.wasBlocked()).isTrue();
        assertThat(result.violation()).contains(ViolationType.INTERNAL_GUARD_ERROR);
    }

    @Test
    void fail_open_guard_allows_on_internal_error() {
        DefaultAgentGuard failingGuard = new DefaultAgentGuard(
                java.util.List.of(tc -> {
                    throw new RuntimeException("Simulated failure");
                }),
                FailSafeMode.FAIL_OPEN,
                null,
                null);

        ToolCall call = ToolCall.of("c-err2", "some_tool");
        GuardResult result = failingGuard.evaluateToolCall(call);

        assertThat(result.isAllowed()).isTrue();
    }
}
