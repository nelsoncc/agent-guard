package io.agentguard.core.policy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class BudgetPolicyTest {

    @Test
    void perRun_creates_policy_without_window() {
        BudgetPolicy policy = BudgetPolicy.perRun(BigDecimal.valueOf(0.10));
        assertThat(policy.maxCost()).isEqualByComparingTo("0.10");
        assertThat(policy.window()).isEmpty();
        assertThat(policy.isRollingWindow()).isFalse();
        assertThat(policy.currency()).isEqualTo("USD");
    }

    @Test
    void perHour_creates_one_hour_rolling_window() {
        BudgetPolicy policy = BudgetPolicy.perHour(BigDecimal.valueOf(2.00));
        assertThat(policy.maxCost()).isEqualByComparingTo("2.00");
        assertThat(policy.window()).contains(Duration.ofHours(1));
        assertThat(policy.isRollingWindow()).isTrue();
    }

    @Test
    void perDay_creates_24h_rolling_window() {
        BudgetPolicy policy = BudgetPolicy.perDay(BigDecimal.valueOf(10.00));
        assertThat(policy.window()).contains(Duration.ofDays(1));
    }

    @Test
    void perRunTokens_creates_token_based_limit() {
        BudgetPolicy policy = BudgetPolicy.perRunTokens(1000);
        assertThat(policy.maxTokens()).isEqualTo(1000L);
        assertThat(policy.window()).isEmpty();
    }

    @Test
    void builder_with_custom_currency() {
        BudgetPolicy policy = BudgetPolicy.builder()
                .maxCost(BigDecimal.valueOf(5.00))
                .currency("EUR")
                .build();
        assertThat(policy.currency()).isEqualTo("EUR");
    }

    @Test
    void builder_with_workspace_and_user_scope() {
        BudgetPolicy policy = BudgetPolicy.builder()
                .maxCost(BigDecimal.ONE)
                .workspaceId("ws-123")
                .userId("user-456")
                .build();
        assertThat(policy.workspaceId()).contains("ws-123");
        assertThat(policy.userId()).contains("user-456");
    }

    @Test
    void builder_rejects_no_limit_set() {
        assertThatThrownBy(() -> BudgetPolicy.builder().build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must define at least one limit");
    }

    @Test
    void builder_rejects_zero_cost() {
        assertThatThrownBy(() -> BudgetPolicy.builder().maxCost(BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_rejects_negative_tokens() {
        assertThatThrownBy(() -> BudgetPolicy.builder().maxTokens(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_rejects_zero_duration_window() {
        assertThatThrownBy(() -> BudgetPolicy.builder()
                .maxCost(BigDecimal.ONE)
                .window(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
