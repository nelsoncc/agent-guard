package io.agentguard.spring;

import io.agentguard.core.AgentGuard;
import io.agentguard.core.policy.*;
import io.agentguard.runtime.PolicyFileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * Creates an {@link AgentGuard} from {@link AgentGuardProperties}.
 *
 * <p>This class is pure Java — no Spring dependency — so it can be used
 * in any context (Spring, Quarkus, plain Java) given a populated properties object.
 */
public final class AgentGuardBuilderFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentGuardBuilderFactory.class);

    private AgentGuardBuilderFactory() {
    }

    /**
     * Builds a fully-configured {@link AgentGuard} from the given properties.
     *
     * @param props populated properties object
     * @return a ready-to-use {@code AgentGuard} instance
     */
    public static AgentGuard build(AgentGuardProperties props) {
        AgentGuard.Builder builder = AgentGuard.builder();

        // Fail-safe mode
        try {
            FailSafeMode mode = FailSafeMode.valueOf(
                    props.getFailSafe().trim().toUpperCase());
            builder.failSafe(mode);
        } catch (IllegalArgumentException e) {
            log.warn("[AgentGuardBuilderFactory] Unknown failSafe '{}', using FAIL_CLOSED",
                    props.getFailSafe());
            builder.failSafe(FailSafeMode.FAIL_CLOSED);
        }

        // Budget
        AgentGuardProperties.Budget budget = props.getBudget();
        if (budget.getPerRunUsd() != null && budget.getPerRunUsd().compareTo(BigDecimal.ZERO) > 0) {
            builder.budget(BudgetPolicy.perRun(budget.getPerRunUsd()));
            log.debug("[AgentGuardBuilderFactory] Budget per-run: ${}", budget.getPerRunUsd());
        }
        if (budget.getPerHourUsd() != null && budget.getPerHourUsd().compareTo(BigDecimal.ZERO) > 0) {
            builder.budget(BudgetPolicy.perHour(budget.getPerHourUsd()));
            log.debug("[AgentGuardBuilderFactory] Budget per-hour: ${}", budget.getPerHourUsd());
        }
        if (budget.getPerDayUsd() != null && budget.getPerDayUsd().compareTo(BigDecimal.ZERO) > 0) {
            builder.budget(BudgetPolicy.perDay(budget.getPerDayUsd()));
            log.debug("[AgentGuardBuilderFactory] Budget per-day: ${}", budget.getPerDayUsd());
        }
        if (budget.getPerRunTokens() > 0) {
            builder.budget(BudgetPolicy.perRunTokens(budget.getPerRunTokens()));
            log.debug("[AgentGuardBuilderFactory] Budget per-run tokens: {}", budget.getPerRunTokens());
        }

        // Loop detection
        AgentGuardProperties.Loop loop = props.getLoop();
        builder.loopDetection(LoopPolicy.builder()
                .maxRepeats(loop.getMaxRepeats())
                .withinLastNCalls(loop.getWindowSize())
                .backoffBeforeInterrupt(loop.isBackoff())
                .semanticDetection(loop.isSemantic())
                .build());

        // Tool policy
        AgentGuardProperties.ToolPolicyConfig tpc = props.getToolPolicy();
        if (tpc.getPolicyFile() != null && !tpc.getPolicyFile().isBlank()) {
            // Load from file (overrides inline config)
            try {
                ToolPolicy filePolicy = PolicyFileLoader.fromFile(Path.of(tpc.getPolicyFile()));
                builder.toolPolicy(filePolicy);
                log.info("[AgentGuardBuilderFactory] Loaded tool policy from: {}", tpc.getPolicyFile());
            } catch (Exception e) {
                log.error("[AgentGuardBuilderFactory] Failed to load policy file '{}': {}",
                        tpc.getPolicyFile(), e.getMessage());
                throw new IllegalStateException("Cannot load policy file: " + tpc.getPolicyFile(), e);
            }
        } else if (!tpc.getAllow().isEmpty() || !tpc.getDeny().isEmpty()
                || !tpc.getRequireConsent().isEmpty()
                || !"BLOCKED".equalsIgnoreCase(tpc.getDefaultAction())) {
            // Build from inline properties
            ToolPolicy.Builder tpBuilder = "BLOCKED".equalsIgnoreCase(tpc.getDefaultAction())
                    ? ToolPolicy.denyAll()
                    : ToolPolicy.allowAll();
            tpc.getAllow().forEach(tpBuilder::allow);
            tpc.getDeny().forEach(tpBuilder::deny);
            tpc.getRequireConsent().forEach(tpBuilder::requireConsent);
            builder.toolPolicy(tpBuilder.build());
            log.debug("[AgentGuardBuilderFactory] Tool policy: default={} allow={} deny={} consent={}",
                    tpc.getDefaultAction(), tpc.getAllow(), tpc.getDeny(), tpc.getRequireConsent());
        }

        // Injection guard
        if (props.getInjection().isEnabled()) {
            InjectionGuardPolicy injPolicy = props.getInjection().isEnforce()
                    ? InjectionGuardPolicy.defaultRules()
                    : InjectionGuardPolicy.auditMode();
            builder.injectionGuard(injPolicy);
            log.debug("[AgentGuardBuilderFactory] Injection guard: enforce={}",
                    props.getInjection().isEnforce());
        }

        AgentGuard guard = builder.build();
        log.info("[AgentGuardBuilderFactory] AgentGuard created for service '{}'",
                props.getServiceName());
        return guard;
    }
}
