package io.agentguard.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class GuardResultTest {

    @Test
    void allowed_result_has_correct_status() {
        GuardResult result = GuardResult.allowed();
        assertThat(result.isAllowed()).isTrue();
        assertThat(result.wasBlocked()).isFalse();
        assertThat(result.requiresConsent()).isFalse();
        assertThat(result.violation()).isEmpty();
        assertThat(result.blockReason()).isEmpty();
    }

    @Test
    void allowed_with_cost_records_metrics() {
        GuardResult result = GuardResult.allowed(1500L, 0.003);
        assertThat(result.isAllowed()).isTrue();
        assertThat(result.tokensConsumed()).isEqualTo(1500L);
        assertThat(result.estimatedCost()).isEqualTo(0.003);
    }

    @Test
    void blocked_result_has_violation_and_reason() {
        GuardResult result = GuardResult.blocked(
                ViolationType.BUDGET_EXCEEDED, "Monthly budget of $10 exhausted");

        assertThat(result.wasBlocked()).isTrue();
        assertThat(result.isAllowed()).isFalse();
        assertThat(result.violation()).contains(ViolationType.BUDGET_EXCEEDED);
        assertThat(result.blockReason()).contains("Monthly budget of $10 exhausted");
    }

    @Test
    void blocked_tool_carries_tool_name() {
        GuardResult result = GuardResult.blockedTool(
                "delete_file", ViolationType.TOOL_DENIED, "Tool is on the denylist");

        assertThat(result.wasBlocked()).isTrue();
        assertThat(result.toolName()).contains("delete_file");
        assertThat(result.violation()).contains(ViolationType.TOOL_DENIED);
    }

    @Test
    void require_consent_has_correct_status_and_violation() {
        GuardResult result = GuardResult.requireConsent("send_email", "High-risk tool");

        assertThat(result.requiresConsent()).isTrue();
        assertThat(result.wasBlocked()).isFalse();
        assertThat(result.toolName()).contains("send_email");
        assertThat(result.violation()).contains(ViolationType.TOOL_REQUIRES_CONSENT);
    }

    @Test
    void builder_sets_all_fields() {
        GuardResult result = GuardResult.builder(GuardStatus.BLOCKED)
                .violation(ViolationType.LOOP_DETECTED)
                .blockReason("Loop: web_search called 3x")
                .toolName("web_search")
                .tokensConsumed(500L)
                .estimatedCost(0.01)
                .build();

        assertThat(result.status()).isEqualTo(GuardStatus.BLOCKED);
        assertThat(result.violation()).contains(ViolationType.LOOP_DETECTED);
        assertThat(result.blockReason()).contains("Loop: web_search called 3x");
        assertThat(result.toolName()).contains("web_search");
        assertThat(result.tokensConsumed()).isEqualTo(500L);
        assertThat(result.estimatedCost()).isEqualTo(0.01);
    }

    @Test
    void timestamp_is_set_automatically() {
        GuardResult result = GuardResult.allowed();
        assertThat(result.timestamp()).isNotNull();
    }

    @Test
    void toString_includes_status() {
        GuardResult allowed = GuardResult.allowed();
        assertThat(allowed.toString()).contains("ALLOWED");

        GuardResult blocked = GuardResult.blocked(ViolationType.TOOL_DENIED, "denied");
        assertThat(blocked.toString()).contains("BLOCKED").contains("TOOL_DENIED");
    }
}
