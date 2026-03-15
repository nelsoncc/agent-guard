package io.agentguard.core.policy;

import io.agentguard.core.GuardStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ToolPolicyTest {

    @Test
    void denyAll_blocks_unknown_tools() {
        ToolPolicy policy = ToolPolicy.denyAll().build();
        assertThat(policy.evaluate("some_unknown_tool")).isEqualTo(GuardStatus.BLOCKED);
    }

    @Test
    void allowAll_permits_unknown_tools() {
        ToolPolicy policy = ToolPolicy.allowAll().build();
        assertThat(policy.evaluate("some_unknown_tool")).isEqualTo(GuardStatus.ALLOWED);
    }

    @Test
    void explicit_allow_overrides_deny_all_default() {
        ToolPolicy policy = ToolPolicy.denyAll()
                .allow("web_search")
                .build();
        assertThat(policy.evaluate("web_search")).isEqualTo(GuardStatus.ALLOWED);
        assertThat(policy.evaluate("delete_file")).isEqualTo(GuardStatus.BLOCKED);
    }

    @Test
    void explicit_deny_overrides_allow_all_default() {
        ToolPolicy policy = ToolPolicy.allowAll()
                .deny("delete_db")
                .build();
        assertThat(policy.evaluate("delete_db")).isEqualTo(GuardStatus.BLOCKED);
        assertThat(policy.evaluate("web_search")).isEqualTo(GuardStatus.ALLOWED);
    }

    @Test
    void consent_required_returns_require_consent_status() {
        ToolPolicy policy = ToolPolicy.denyAll()
                .allow("read_file")
                .requireConsent("send_email")
                .build();
        assertThat(policy.evaluate("send_email")).isEqualTo(GuardStatus.REQUIRE_CONSENT);
        assertThat(policy.evaluate("read_file")).isEqualTo(GuardStatus.ALLOWED);
    }

    @Test
    void deny_takes_priority_over_consent() {
        // A tool on the denylist should be blocked even if also in consent list.
        // denylist is checked first.
        ToolPolicy policy = ToolPolicy.allowAll()
                .deny("dangerous_tool")
                .requireConsent("dangerous_tool")
                .build();
        // deny is evaluated before consent in the chain
        assertThat(policy.evaluate("dangerous_tool")).isEqualTo(GuardStatus.BLOCKED);
    }

    @Test
    void tool_names_are_normalised_to_lowercase() {
        ToolPolicy policy = ToolPolicy.denyAll()
                .allow("Web_Search")
                .build();
        assertThat(policy.evaluate("web_search")).isEqualTo(GuardStatus.ALLOWED);
        assertThat(policy.evaluate("WEB_SEARCH")).isEqualTo(GuardStatus.ALLOWED);
    }

    @Test
    void critical_risk_tool_is_blocked_by_default() {
        ToolPolicy policy = ToolPolicy.allowAll()
                .withRisk("nuke_everything", ToolRisk.CRITICAL)
                .build();
        assertThat(policy.evaluate("nuke_everything")).isEqualTo(GuardStatus.BLOCKED);
    }

    @Test
    void high_risk_tool_requires_consent_by_default() {
        ToolPolicy policy = ToolPolicy.allowAll()
                .withRisk("send_email", ToolRisk.HIGH)
                .build();
        assertThat(policy.evaluate("send_email")).isEqualTo(GuardStatus.REQUIRE_CONSENT);
    }

    @Test
    void builder_rejects_tool_in_both_allow_and_deny() {
        assertThatThrownBy(() -> ToolPolicy.denyAll()
                .allow("my_tool")
                .deny("my_tool")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("my_tool");
    }

    @Test
    void readme_example_policy_behaves_as_documented() {
        ToolPolicy policy = ToolPolicy.denyAll()
                .allow("web_search")
                .allow("read_file")
                .deny("delete_file")
                .requireConsent("send_email")
                .build();

        assertThat(policy.evaluate("web_search")).isEqualTo(GuardStatus.ALLOWED);
        assertThat(policy.evaluate("read_file")).isEqualTo(GuardStatus.ALLOWED);
        assertThat(policy.evaluate("delete_file")).isEqualTo(GuardStatus.BLOCKED);
        assertThat(policy.evaluate("send_email")).isEqualTo(GuardStatus.REQUIRE_CONSENT);
        assertThat(policy.evaluate("unknown_tool")).isEqualTo(GuardStatus.BLOCKED); // deny-all default
    }
}
