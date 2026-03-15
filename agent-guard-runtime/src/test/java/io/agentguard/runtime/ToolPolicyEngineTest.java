package io.agentguard.runtime;

import io.agentguard.core.GuardResult;
import io.agentguard.core.GuardStatus;
import io.agentguard.core.ToolCall;
import io.agentguard.core.ViolationType;
import io.agentguard.core.policy.ExecutionContext;
import io.agentguard.core.policy.ExecutionContext.Environment;
import io.agentguard.core.policy.ToolPolicy;
import io.agentguard.core.policy.ToolRisk;
import io.agentguard.core.spi.ConsentHandler;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Milestone 3 — Tool Policy Engine (Issues #16–#21).
 * <p>
 * Structure:
 * - AllowDenyTests         — Issue #16: allowlist / denylist enforcement
 * - RiskScoringTests       — Issue #17: per-tool risk scoring
 * - ConsentFlowTests       — Issue #18: REQUIRE_CONSENT with/without handler
 * - ContextAwareTests      — Issue #19: environment-scoped policy rules
 * - PolicyFileLoaderTests  — Issue #20: YAML/JSON policy-as-code loading
 * - IntegrationTests       — Issue #21: end-to-end via AgentGuard.builder()
 */
class ToolPolicyEngineTest {

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static ToolCall call(String tool) {
        return ToolCall.of("id-" + tool, tool);
    }

    private static ToolCall callWithContext(String tool, Environment env) {
        return ToolCall.builder("id-" + tool, tool)
                .context(ExecutionContext.builder().environment(env).build())
                .build();
    }

    // ─── Issue #16: allowlist / denylist ─────────────────────────────────────

    @Nested
    class AllowDenyTests {

        @Test
        void deny_all_blocks_unknown_tool() {
            ToolPolicyEngine engine = new ToolPolicyEngine(ToolPolicy.denyAll().build());
            GuardResult result = engine.evaluate(call("unknown_tool"));

            assertThat(result.wasBlocked()).isTrue();
            assertThat(result.violation()).contains(ViolationType.TOOL_DENIED);
        }

        @Test
        void allow_all_permits_unknown_tool() {
            ToolPolicyEngine engine = new ToolPolicyEngine(ToolPolicy.allowAll().build());
            assertThat(engine.evaluate(call("anything")).isAllowed()).isTrue();
        }

        @Test
        void explicitly_allowed_tool_passes_in_deny_all_mode() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll().allow("web_search").build());

            assertThat(engine.evaluate(call("web_search")).isAllowed()).isTrue();
            assertThat(engine.evaluate(call("read_file")).wasBlocked()).isTrue();
        }

        @Test
        void explicitly_denied_tool_is_blocked_in_allow_all_mode() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.allowAll().deny("delete_db").build());

            assertThat(engine.evaluate(call("delete_db")).wasBlocked()).isTrue();
            assertThat(engine.evaluate(call("web_search")).isAllowed()).isTrue();
        }

        @Test
        void block_reason_mentions_tool_name() {
            ToolPolicyEngine engine = new ToolPolicyEngine(ToolPolicy.denyAll().build());
            GuardResult result = engine.evaluate(call("evil_tool"));

            assertThat(result.blockReason()).isPresent();
            assertThat(result.blockReason().get()).contains("evil_tool");
        }

        @Test
        void tool_names_evaluated_case_insensitively() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll().allow("Web_Search").build());

            assertThat(engine.evaluate(call("WEB_SEARCH")).isAllowed()).isTrue();
            assertThat(engine.evaluate(call("web_search")).isAllowed()).isTrue();
        }

        @Test
        void readme_scenario_matches_documented_behaviour() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll()
                            .allow("web_search")
                            .allow("read_file")
                            .requireConsent("send_email")
                            .deny("delete_db")
                            .build());

            assertThat(engine.evaluate(call("web_search")).isAllowed()).isTrue();       // ALLOW
            assertThat(engine.evaluate(call("read_file")).isAllowed()).isTrue();         // ALLOW
            assertThat(engine.evaluate(call("delete_db")).wasBlocked()).isTrue();        // BLOCK
            assertThat(engine.evaluate(call("unknown")).wasBlocked()).isTrue();          // BLOCK (deny-all)
            // send_email → REQUIRE_CONSENT, but no handler → blocked
            assertThat(engine.evaluate(call("send_email")).wasBlocked()).isTrue();
            assertThat(engine.evaluate(call("send_email")).violation())
                    .contains(ViolationType.TOOL_REQUIRES_CONSENT);
        }
    }

    // ─── Issue #17: per-tool risk scoring ────────────────────────────────────

    @Nested
    class RiskScoringTests {

        @Test
        void critical_risk_tool_is_blocked() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.allowAll()
                            .withRisk("nuke_prod", ToolRisk.CRITICAL)
                            .build());

            GuardResult result = engine.evaluate(call("nuke_prod"));
            assertThat(result.wasBlocked()).isTrue();
            assertThat(result.violation()).contains(ViolationType.TOOL_DENIED);
            assertThat(result.blockReason().get()).containsIgnoringCase("CRITICAL");
        }

        @Test
        void high_risk_tool_requires_consent_when_no_handler() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.allowAll()
                            .withRisk("send_email", ToolRisk.HIGH)
                            .build());

            GuardResult result = engine.evaluate(call("send_email"));
            assertThat(result.wasBlocked()).isTrue();
            assertThat(result.violation()).contains(ViolationType.TOOL_REQUIRES_CONSENT);
        }

        @Test
        void medium_risk_tool_is_allowed_by_default() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.allowAll()
                            .withRisk("read_file", ToolRisk.MEDIUM)
                            .build());
            assertThat(engine.evaluate(call("read_file")).isAllowed()).isTrue();
        }

        @Test
        void low_risk_tool_is_allowed() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.allowAll()
                            .withRisk("ping", ToolRisk.LOW)
                            .build());
            assertThat(engine.evaluate(call("ping")).isAllowed()).isTrue();
        }

        @Test
        void explicit_deny_takes_priority_over_any_risk_level() {
            // Even MEDIUM risk on a denylisted tool → BLOCKED with TOOL_DENIED
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.allowAll()
                            .deny("restricted_tool")
                            .withRisk("restricted_tool", ToolRisk.MEDIUM)
                            .build());
            GuardResult result = engine.evaluate(call("restricted_tool"));
            assertThat(result.wasBlocked()).isTrue();
            assertThat(result.violation()).contains(ViolationType.TOOL_DENIED);
        }
    }

    // ─── Issue #18: REQUIRE_CONSENT flow ─────────────────────────────────────

    @Nested
    class ConsentFlowTests {

        @Test
        void require_consent_without_handler_blocks_with_consent_violation() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll().requireConsent("send_email").build());

            GuardResult result = engine.evaluate(call("send_email"));
            assertThat(result.wasBlocked()).isTrue();
            assertThat(result.violation()).contains(ViolationType.TOOL_REQUIRES_CONSENT);
            assertThat(result.blockReason().get()).containsIgnoringCase("no ConsentHandler");
        }

        @Test
        void require_consent_with_approving_handler_returns_allowed() {
            ConsentHandler approver = (toolCall, reason) ->
                    CompletableFuture.completedFuture(true);

            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll().requireConsent("send_email").build(),
                    approver);

            GuardResult result = engine.evaluate(call("send_email"));
            assertThat(result.isAllowed()).isTrue();
        }

        @Test
        void require_consent_with_denying_handler_returns_blocked() {
            ConsentHandler denier = (toolCall, reason) ->
                    CompletableFuture.completedFuture(false);

            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll().requireConsent("send_email").build(),
                    denier);

            GuardResult result = engine.evaluate(call("send_email"));
            assertThat(result.wasBlocked()).isTrue();
            assertThat(result.violation()).contains(ViolationType.TOOL_REQUIRES_CONSENT);
            assertThat(result.blockReason().get()).containsIgnoringCase("denied");
        }

        @Test
        void consent_timeout_fails_closed() {
            // Handler never completes → timeout should trigger
            ConsentHandler neverResponds = (toolCall, reason) ->
                    new CompletableFuture<>(); // never completes

            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll().requireConsent("send_email").build(),
                    neverResponds,
                    1L); // 1-second timeout for test speed

            GuardResult result = engine.evaluate(call("send_email"));
            assertThat(result.wasBlocked()).isTrue();
            assertThat(result.violation()).contains(ViolationType.TOOL_REQUIRES_CONSENT);
            assertThat(result.blockReason().get()).containsIgnoringCase("timed out");
        }

        @Test
        void consent_handler_exception_fails_closed() {
            ConsentHandler throwing = (toolCall, reason) -> {
                throw new RuntimeException("Consent service unavailable");
            };

            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll().requireConsent("send_email").build(),
                    throwing);

            GuardResult result = engine.evaluate(call("send_email"));
            assertThat(result.wasBlocked()).isTrue();
            assertThat(result.violation()).contains(ViolationType.TOOL_REQUIRES_CONSENT);
        }

        @Test
        void handler_receives_correct_tool_call_and_reason() {
            var capturedCall = new ToolCall[1];
            var capturedReason = new String[1];

            ConsentHandler capturing = (toolCall, reason) -> {
                capturedCall[0] = toolCall;
                capturedReason[0] = reason;
                return CompletableFuture.completedFuture(true);
            };

            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll().requireConsent("send_email").build(),
                    capturing);

            engine.evaluate(call("send_email"));

            assertThat(capturedCall[0].toolName()).isEqualTo("send_email");
            assertThat(capturedReason[0]).isNotNull().isNotBlank();
        }
    }

    // ─── Issue #19: context-aware policies ───────────────────────────────────

    @Nested
    class ContextAwareTests {

        @Test
        void tool_allowed_only_in_dev_is_blocked_in_prod() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll()
                            .allowInEnvironment("debug_tool", Environment.DEV)
                            .build());

            assertThat(engine.evaluate(callWithContext("debug_tool", Environment.DEV)).isAllowed()).isTrue();
            assertThat(engine.evaluate(callWithContext("debug_tool", Environment.PROD)).wasBlocked()).isTrue();
            assertThat(engine.evaluate(callWithContext("debug_tool", Environment.STAGING)).wasBlocked()).isTrue();
        }

        @Test
        void tool_denied_in_prod_falls_through_to_base_rule_in_other_envs() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.allowAll()
                            .denyInEnvironment("risky_tool", Environment.PROD)
                            .build());

            assertThat(engine.evaluate(callWithContext("risky_tool", Environment.PROD)).wasBlocked()).isTrue();
            assertThat(engine.evaluate(callWithContext("risky_tool", Environment.DEV)).isAllowed()).isTrue();
        }

        @Test
        void tool_requires_consent_only_in_staging() {
            ConsentHandler approver = (tc, r) -> CompletableFuture.completedFuture(true);

            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.allowAll()
                            .requireConsentInEnvironment("deploy", Environment.STAGING)
                            .build(),
                    approver);

            // STAGING → consent requested → handler approves → ALLOWED
            assertThat(engine.evaluate(callWithContext("deploy", Environment.STAGING)).isAllowed()).isTrue();
            // DEV/PROD → no context rule matches → falls to base allow-all
            assertThat(engine.evaluate(callWithContext("deploy", Environment.DEV)).isAllowed()).isTrue();
            assertThat(engine.evaluate(callWithContext("deploy", Environment.PROD)).isAllowed()).isTrue();
        }

        @Test
        void context_rule_without_env_filter_matches_all_environments() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll()
                            .addContextRule(new ToolPolicy.ContextRule("universal_tool", GuardStatus.ALLOWED, null))
                            .build());

            assertThat(engine.evaluate(callWithContext("universal_tool", Environment.DEV)).isAllowed()).isTrue();
            assertThat(engine.evaluate(callWithContext("universal_tool", Environment.STAGING)).isAllowed()).isTrue();
            assertThat(engine.evaluate(callWithContext("universal_tool", Environment.PROD)).isAllowed()).isTrue();
        }

        @Test
        void context_rules_checked_before_base_denylist() {
            // deny_all base, but context rule allows in DEV — context rule wins
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll()
                            .deny("internal_tool")
                            .allowInEnvironment("internal_tool", Environment.DEV)
                            .build());

            // Context rule fires first → ALLOWED in DEV despite being on denylist
            assertThat(engine.evaluate(callWithContext("internal_tool", Environment.DEV)).isAllowed()).isTrue();
            // No context rule for PROD → falls to denylist → BLOCKED
            assertThat(engine.evaluate(callWithContext("internal_tool", Environment.PROD)).wasBlocked()).isTrue();
        }

        @Test
        void tool_call_without_context_skips_context_rules() {
            ToolPolicyEngine engine = new ToolPolicyEngine(
                    ToolPolicy.denyAll()
                            .allowInEnvironment("debug_tool", Environment.DEV)
                            .build());

            // No context attached → context rules skipped → falls to denylist
            assertThat(engine.evaluate(call("debug_tool")).wasBlocked()).isTrue();
        }

        @Test
        void environment_parsed_from_string_correctly() {
            assertThat(Environment.fromString("dev")).isEqualTo(Environment.DEV);
            assertThat(Environment.fromString("STAGING")).isEqualTo(Environment.STAGING);
            assertThat(Environment.fromString("production")).isEqualTo(Environment.PROD);
            assertThat(Environment.fromString(null)).isEqualTo(Environment.PROD);
        }

        @Test
        void execution_context_tags_accessible() {
            ExecutionContext ctx = ExecutionContext.builder()
                    .environment(Environment.PROD)
                    .userId("user-42")
                    .tag("role", "admin")
                    .build();

            assertThat(ctx.isProd()).isTrue();
            assertThat(ctx.userId()).contains("user-42");
            assertThat(ctx.tag("role")).contains("admin");
            assertThat(ctx.tag("missing")).isEmpty();
        }
    }

    // ─── Issue #20: policy-as-code (YAML/JSON) ───────────────────────────────

    @Nested
    class PolicyFileLoaderTests {

        @Test
        void loads_yaml_from_classpath() {
            ToolPolicy policy = PolicyFileLoader.fromClasspath("test-policy.yaml");
            assertThat(policy).isNotNull();
        }

        @Test
        void yaml_allowlist_is_loaded() {
            ToolPolicy policy = PolicyFileLoader.fromClasspath("test-policy.yaml");
            assertThat(policy.allowlist()).contains("web_search", "read_file");
        }

        @Test
        void yaml_denylist_is_loaded() {
            ToolPolicy policy = PolicyFileLoader.fromClasspath("test-policy.yaml");
            assertThat(policy.denylist()).contains("delete_db");
        }

        @Test
        void yaml_consent_list_is_loaded() {
            ToolPolicy policy = PolicyFileLoader.fromClasspath("test-policy.yaml");
            assertThat(policy.consentRequired()).contains("send_email");
        }

        @Test
        void yaml_default_blocked_enforced() {
            ToolPolicy policy = PolicyFileLoader.fromClasspath("test-policy.yaml");
            assertThat(policy.defaultAction()).isEqualTo(GuardStatus.BLOCKED);
        }

        @Test
        void yaml_critical_risk_overrides_loaded() {
            ToolPolicy policy = PolicyFileLoader.fromClasspath("test-policy.yaml");
            assertThat(policy.effectiveRisk("nuke_everything")).isEqualTo(ToolRisk.CRITICAL);
        }

        @Test
        void yaml_high_risk_overrides_loaded() {
            ToolPolicy policy = PolicyFileLoader.fromClasspath("test-policy.yaml");
            assertThat(policy.effectiveRisk("write_file")).isEqualTo(ToolRisk.HIGH);
        }

        @Test
        void yaml_context_rule_allow_in_dev() {
            ToolPolicy policy = PolicyFileLoader.fromClasspath("test-policy.yaml");
            // debug_tool has context rule: ALLOW in DEV
            assertThat(policy.contextRules()).anyMatch(r ->
                    r.toolName().equals("debug_tool")
                            && r.action() == GuardStatus.ALLOWED
                            && r.environment() == Environment.DEV);
        }

        @Test
        void yaml_context_rule_any_environment_loaded() {
            ToolPolicy policy = PolicyFileLoader.fromClasspath("test-policy.yaml");
            // admin_reset has context rule with no environment filter
            assertThat(policy.contextRules()).anyMatch(r ->
                    r.toolName().equals("admin_reset")
                            && r.action() == GuardStatus.REQUIRE_CONSENT
                            && r.environment() == null);
        }

        @Test
        void loaded_yaml_policy_evaluates_correctly_via_engine() {
            ToolPolicy policy = PolicyFileLoader.fromClasspath("test-policy.yaml");
            ToolPolicyEngine engine = new ToolPolicyEngine(policy);

            assertThat(engine.evaluate(call("web_search")).isAllowed()).isTrue();
            assertThat(engine.evaluate(call("delete_db")).wasBlocked()).isTrue();
            // send_email → REQUIRE_CONSENT but no handler → blocked
            assertThat(engine.evaluate(call("send_email")).violation())
                    .contains(ViolationType.TOOL_REQUIRES_CONSENT);
            // debug_tool with DEV context → ALLOW via context rule
            assertThat(engine.evaluate(callWithContext("debug_tool", Environment.DEV)).isAllowed()).isTrue();
        }

        @Test
        void loads_json_from_classpath() {
            ToolPolicy policy = PolicyFileLoader.fromClasspath("test-policy.json");
            assertThat(policy).isNotNull();
            assertThat(policy.allowlist()).contains("web_search");
            assertThat(policy.denylist()).contains("delete_db");
            assertThat(policy.defaultAction()).isEqualTo(GuardStatus.BLOCKED);
        }

        @Test
        void loads_from_filesystem_path() throws Exception {
            // Write a temp YAML file and load it
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("policy-test", ".yaml");
            java.nio.file.Files.writeString(tmp,
                    "default: ALLOWED\nallow:\n  - ping\ndeny:\n  - drop_table\n");
            try {
                ToolPolicy policy = PolicyFileLoader.fromFile(tmp);
                assertThat(policy.defaultAction()).isEqualTo(GuardStatus.ALLOWED);
                assertThat(policy.allowlist()).contains("ping");
                assertThat(policy.denylist()).contains("drop_table");
            } finally {
                java.nio.file.Files.deleteIfExists(tmp);
            }
        }

        @Test
        void missing_classpath_resource_throws_policy_load_exception() {
            assertThatThrownBy(() -> PolicyFileLoader.fromClasspath("does-not-exist.yaml"))
                    .isInstanceOf(PolicyFileLoader.PolicyLoadException.class)
                    .hasMessageContaining("does-not-exist.yaml");
        }

        @Test
        void missing_filesystem_path_throws_policy_load_exception() {
            assertThatThrownBy(() ->
                    PolicyFileLoader.fromFile(Path.of("/tmp/no-such-policy-file.yaml")))
                    .isInstanceOf(PolicyFileLoader.PolicyLoadException.class);
        }
    }

    // ─── Issue #21: end-to-end via AgentGuard.builder() ──────────────────────

    @Nested
    class IntegrationTests {

        @Test
        void tool_policy_engine_wired_via_agent_guard_builder() {
            var guard = io.agentguard.core.AgentGuard.builder()
                    .toolPolicy(ToolPolicy.denyAll()
                            .allow("web_search")
                            .deny("delete_db")
                            .build())
                    .build();

            assertThat(guard.evaluateToolCall(call("web_search")).isAllowed()).isTrue();
            assertThat(guard.evaluateToolCall(call("delete_db")).wasBlocked()).isTrue();
            assertThat(guard.evaluateToolCall(call("unknown")).wasBlocked()).isTrue();
        }

        @Test
        void consent_handler_wired_via_builder() {
            var guard = io.agentguard.core.AgentGuard.builder()
                    .toolPolicy(ToolPolicy.denyAll()
                            .requireConsent("send_email")
                            .build())
                    .consentHandler((tc, reason) -> CompletableFuture.completedFuture(true))
                    .consentTimeoutSeconds(10)
                    .build();

            assertThat(guard.evaluateToolCall(call("send_email")).isAllowed()).isTrue();
        }

        @Test
        void no_tool_policy_skips_engine_and_allows_all() {
            // Builder without toolPolicy → ToolPolicyEngine not added → allow all
            var guard = io.agentguard.core.AgentGuard.builder().build();
            assertThat(guard.evaluateToolCall(call("any_tool")).isAllowed()).isTrue();
        }

        @Test
        void context_aware_policy_enforced_end_to_end() {
            var guard = io.agentguard.core.AgentGuard.builder()
                    .toolPolicy(ToolPolicy.denyAll()
                            .allowInEnvironment("debug_tool", Environment.DEV)
                            .build())
                    .build();

            ToolCall devCall = ToolCall.builder("c1", "debug_tool")
                    .context(ExecutionContext.dev()).build();
            ToolCall prodCall = ToolCall.builder("c2", "debug_tool")
                    .context(ExecutionContext.prod()).build();

            assertThat(guard.evaluateToolCall(devCall).isAllowed()).isTrue();
            assertThat(guard.evaluateToolCall(prodCall).wasBlocked()).isTrue();
        }

        @Test
        void policy_from_yaml_file_wired_end_to_end() {
            ToolPolicy policy = PolicyFileLoader.fromClasspath("test-policy.yaml");

            var guard = io.agentguard.core.AgentGuard.builder()
                    .toolPolicy(policy)
                    .build();

            assertThat(guard.evaluateToolCall(call("web_search")).isAllowed()).isTrue();
            assertThat(guard.evaluateToolCall(call("delete_db")).wasBlocked()).isTrue();
        }
    }
}
