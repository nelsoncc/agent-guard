package io.agentguard.quarkus;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Quarkus configuration interface for Agent Guard (Issue #37).
 *
 * <p>Uses SmallRye {@link ConfigMapping} for Quarkus-native configuration binding,
 * including GraalVM native-image support.
 *
 * <p>Example {@code application.properties}:
 * <pre>{@code
 * agent-guard.enabled=true
 * agent-guard.service-name=my-quarkus-agent
 * agent-guard.fail-safe=FAIL_CLOSED
 * agent-guard.budget.per-run-usd=0.50
 * agent-guard.budget.per-hour-usd=2.00
 * agent-guard.loop.max-repeats=3
 * agent-guard.loop.window-size=10
 * agent-guard.tool-policy.default-action=BLOCKED
 * agent-guard.tool-policy.allow=web_search,read_file
 * agent-guard.injection.enabled=true
 * agent-guard.injection.enforce=true
 * }</pre>
 */
@ConfigMapping(prefix = "agent-guard")
public interface AgentGuardConfig {

    /**
     * Whether Agent Guard is active. Default: true.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Service name for OTel/Micrometer tagging.
     */
    @WithDefault("agent-guard")
    @WithName("service-name")
    String serviceName();

    /**
     * Fail-safe mode: FAIL_CLOSED or FAIL_OPEN.
     */
    @WithDefault("FAIL_CLOSED")
    @WithName("fail-safe")
    String failSafe();

    /**
     * Budget configuration.
     */
    BudgetConfig budget();

    /**
     * Loop detection configuration.
     */
    LoopConfig loop();

    /**
     * Tool policy configuration.
     */
    @WithName("tool-policy")
    ToolPolicyConfig toolPolicy();

    /**
     * Injection guard configuration.
     */
    InjectionConfig injection();

    // ─── Nested config interfaces ─────────────────────────────────────────────

    interface BudgetConfig {
        @WithName("per-run-usd")
        Optional<BigDecimal> perRunUsd();

        @WithName("per-hour-usd")
        Optional<BigDecimal> perHourUsd();

        @WithName("per-day-usd")
        Optional<BigDecimal> perDayUsd();

        @WithDefault("0")
        @WithName("per-run-tokens")
        long perRunTokens();
    }

    interface LoopConfig {
        @WithDefault("3")
        @WithName("max-repeats")
        int maxRepeats();

        @WithDefault("10")
        @WithName("window-size")
        int windowSize();

        @WithDefault("true")
        boolean backoff();

        @WithDefault("false")
        boolean semantic();
    }

    interface ToolPolicyConfig {
        @WithDefault("BLOCKED")
        @WithName("default-action")
        String defaultAction();

        Optional<List<String>> allow();

        Optional<List<String>> deny();

        @WithName("require-consent")
        Optional<List<String>> requireConsent();

        @WithName("policy-file")
        Optional<String> policyFile();
    }

    interface InjectionConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("true")
        boolean enforce();
    }
}
