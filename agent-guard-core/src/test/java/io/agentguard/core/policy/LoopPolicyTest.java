package io.agentguard.core.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class LoopPolicyTest {

    @Test
    void defaults_have_sensible_values() {
        LoopPolicy policy = LoopPolicy.defaults();
        assertThat(policy.maxRepeats()).isEqualTo(LoopPolicy.DEFAULT_MAX_REPEATS);
        assertThat(policy.windowSize()).isEqualTo(LoopPolicy.DEFAULT_WINDOW_SIZE);
        assertThat(policy.backoffBeforeInterrupt()).isTrue();
        assertThat(policy.semanticDetectionEnabled()).isFalse();
    }

    @Test
    void disabled_sets_max_repeats_to_int_max() {
        LoopPolicy policy = LoopPolicy.disabled();
        assertThat(policy.maxRepeats()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void readme_fluent_api_works() {
        LoopPolicy policy = LoopPolicy.maxRepeats(3).withinLastNCalls(10).build();
        assertThat(policy.maxRepeats()).isEqualTo(3);
        assertThat(policy.windowSize()).isEqualTo(10);
    }

    @Test
    void builder_rejects_zero_max_repeats() {
        assertThatThrownBy(() -> LoopPolicy.builder().maxRepeats(0).build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_rejects_max_repeats_exceeding_window() {
        assertThatThrownBy(() -> LoopPolicy.builder()
                .maxRepeats(5)
                .withinLastNCalls(3)
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot exceed windowSize");
    }
}
