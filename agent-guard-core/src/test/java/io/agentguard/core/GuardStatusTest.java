package io.agentguard.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class GuardStatusTest {

    @Test
    void allowed_is_allowed_and_not_blocked() {
        assertThat(GuardStatus.ALLOWED.isAllowed()).isTrue();
        assertThat(GuardStatus.ALLOWED.isBlocked()).isFalse();
        assertThat(GuardStatus.ALLOWED.requiresConsent()).isFalse();
    }

    @Test
    void blocked_is_blocked_and_not_allowed() {
        assertThat(GuardStatus.BLOCKED.isBlocked()).isTrue();
        assertThat(GuardStatus.BLOCKED.isAllowed()).isFalse();
        assertThat(GuardStatus.BLOCKED.requiresConsent()).isFalse();
    }

    @Test
    void require_consent_is_not_allowed_and_not_blocked() {
        assertThat(GuardStatus.REQUIRE_CONSENT.requiresConsent()).isTrue();
        assertThat(GuardStatus.REQUIRE_CONSENT.isAllowed()).isFalse();
        assertThat(GuardStatus.REQUIRE_CONSENT.isBlocked()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(GuardStatus.class)
    void exactly_one_flag_is_true_for_each_status(GuardStatus status) {
        int trueCount = (status.isAllowed() ? 1 : 0)
                      + (status.isBlocked() ? 1 : 0)
                      + (status.requiresConsent() ? 1 : 0);
        assertThat(trueCount)
            .as("Exactly one flag should be true for %s", status)
            .isEqualTo(1);
    }
}
