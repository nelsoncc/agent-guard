package io.agentguard.runtime;

import io.agentguard.core.AgentGuard;
import io.agentguard.core.AgentGuardFactory;
import io.agentguard.core.spi.ToolGuard;

import java.util.ArrayList;
import java.util.List;

/**
 * ServiceLoader-registered factory that creates {@link DefaultAgentGuard} instances.
 *
 * <p>Registered in {@code META-INF/services/io.agentguard.core.AgentGuardFactory}.
 * Called by {@link AgentGuard.Builder#build()} when the runtime module is on the classpath.
 *
 * <p>Guard chain evaluation order:
 * <ol>
 *   <li>InjectionGuard   — input scanning (Milestone 4)</li>
 *   <li>BudgetFirewall   — cost pre-check (Milestone 1)</li>
 *   <li>LoopDetector     — repetition check (Milestone 2)</li>
 *   <li>ToolPolicyEngine — allowlist/denylist/consent (Milestone 3)</li>
 * </ol>
 */
public class DefaultAgentGuardFactory implements AgentGuardFactory {

    @Override
    public AgentGuard create(AgentGuard.Builder builder) {
        List<ToolGuard> chain = new ArrayList<>();

        // Milestone 4: InjectionGuard — added first so injected content is caught
        // before budget is consumed or policy is checked
        if (builder.injectionGuardPolicy() != null) {
            chain.add(new InjectionGuard(builder.injectionGuardPolicy()));
        }

        // Milestone 1: BudgetFirewall
        BudgetFirewall budgetFirewall = null;
        if (!builder.budgets().isEmpty()) {
            budgetFirewall = new BudgetFirewall(builder.budgets(), TokenCostTable.defaults());
            chain.add(budgetFirewall);
        }

        // Milestone 2: LoopDetector — always added
        chain.add(new LoopDetector(builder.loopPolicy()));

        // Milestone 3: ToolPolicyEngine
        if (builder.toolPolicy() != null) {
            chain.add(new ToolPolicyEngine(
                    builder.toolPolicy(),
                    builder.consentHandler(),
                    builder.consentTimeoutSeconds()));
        }

        return new DefaultAgentGuard(chain, builder.failSafeMode(), builder.consentHandler(),
                budgetFirewall);
    }
}
