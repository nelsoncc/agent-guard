package io.agentguard.quarkus;

import io.agentguard.core.AgentGuard;
import io.agentguard.core.policy.*;
import io.agentguard.runtime.PolicyFileLoader;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * CDI bean producer for Agent Guard in Quarkus (Issue #37).
 *
 * <p>This class is an {@link ApplicationScoped} CDI bean that reads
 * {@link AgentGuardConfig} (bound via SmallRye {@code @ConfigMapping}) and
 * produces a fully-configured {@link AgentGuard} instance.
 *
 * <p>The {@link DefaultBean} annotation allows applications to override the guard
 * by declaring their own {@code AgentGuard} producer with higher priority.
 *
 * <p>GraalVM native-image: all guard classes use standard Java (no reflection on
 * user classes), so they are fully native-compatible out of the box.
 */
@ApplicationScoped
public class AgentGuardProducer {

    private static final Logger log = LoggerFactory.getLogger(AgentGuardProducer.class);

    @Inject
    AgentGuardConfig config;

    /**
     * CDI no-arg constructor (required by spec).
     */
    public AgentGuardProducer() {
    }

    /**
     * Constructor for programmatic use outside CDI context (e.g., testing).
     *
     * @param config the configuration binding
     */
    public AgentGuardProducer(AgentGuardConfig config) {
        this.config = config;
    }

    /**
     * Produces the application-scoped {@link AgentGuard} bean.
     *
     * <p>Marked as {@link DefaultBean} so that application code can override
     * it by declaring its own {@code @Produces AgentGuard} method.
     */
    @Produces
    @ApplicationScoped
    @DefaultBean
    public AgentGuard agentGuard() {
        if (!config.enabled()) {
            log.info("[AgentGuardProducer] Agent Guard disabled — returning passthrough guard");
            return AgentGuard.builder().build();
        }

        AgentGuard.Builder builder = AgentGuard.builder();

        // Fail-safe
        try {
            builder.failSafe(FailSafeMode.valueOf(config.failSafe().toUpperCase()));
        } catch (IllegalArgumentException e) {
            log.warn("[AgentGuardProducer] Unknown failSafe '{}', defaulting to FAIL_CLOSED",
                    config.failSafe());
            builder.failSafe(FailSafeMode.FAIL_CLOSED);
        }

        // Budget
        config.budget().perRunUsd()
                .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
                .ifPresent(v -> {
                    builder.budget(BudgetPolicy.perRun(v));
                    log.debug("[AgentGuardProducer] per-run budget ${}", v);
                });
        config.budget().perHourUsd()
                .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
                .ifPresent(v -> {
                    builder.budget(BudgetPolicy.perHour(v));
                    log.debug("[AgentGuardProducer] per-hour budget ${}", v);
                });
        config.budget().perDayUsd()
                .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
                .ifPresent(v -> {
                    builder.budget(BudgetPolicy.perDay(v));
                    log.debug("[AgentGuardProducer] per-day budget ${}", v);
                });
        if (config.budget().perRunTokens() > 0) {
            builder.budget(BudgetPolicy.perRunTokens(config.budget().perRunTokens()));
        }

        // Loop detection
        builder.loopDetection(LoopPolicy.builder()
                .maxRepeats(config.loop().maxRepeats())
                .withinLastNCalls(config.loop().windowSize())
                .backoffBeforeInterrupt(config.loop().backoff())
                .semanticDetection(config.loop().semantic())
                .build());

        // Tool policy
        AgentGuardConfig.ToolPolicyConfig tpc = config.toolPolicy();
        Optional<String> policyFile = tpc.policyFile();
        if (policyFile.isPresent() && !policyFile.get().isBlank()) {
            try {
                builder.toolPolicy(PolicyFileLoader.fromFile(Path.of(policyFile.get())));
                log.info("[AgentGuardProducer] Loaded policy from {}", policyFile.get());
            } catch (Exception e) {
                throw new IllegalStateException("Cannot load policy file: " + policyFile.get(), e);
            }
        } else {
            List<String> allow = tpc.allow().orElse(List.of());
            List<String> deny = tpc.deny().orElse(List.of());
            List<String> consent = tpc.requireConsent().orElse(List.of());
            if (!allow.isEmpty() || !deny.isEmpty() || !consent.isEmpty()
                    || !"BLOCKED".equalsIgnoreCase(tpc.defaultAction())) {
                ToolPolicy.Builder tp = "BLOCKED".equalsIgnoreCase(tpc.defaultAction())
                        ? ToolPolicy.denyAll() : ToolPolicy.allowAll();
                allow.forEach(tp::allow);
                deny.forEach(tp::deny);
                consent.forEach(tp::requireConsent);
                builder.toolPolicy(tp.build());
            }
        }

        // Injection guard
        if (config.injection().enabled()) {
            builder.injectionGuard(config.injection().enforce()
                    ? InjectionGuardPolicy.defaultRules()
                    : InjectionGuardPolicy.auditMode());
        }

        AgentGuard guard = builder.build();
        log.info("[AgentGuardProducer] AgentGuard ready — service='{}'", config.serviceName());
        return guard;
    }
}
