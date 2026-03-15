package io.agentguard.core.spi;

import io.agentguard.core.GuardResult;
import io.agentguard.core.ToolCall;

/**
 * SPI: a component that evaluates a single tool call against some policy.
 *
 * <p>Implementations include:
 * <ul>
 *   <li>{@code BudgetFirewall} — checks remaining budget before the call</li>
 *   <li>{@code ToolPolicyEngine} — checks allowlist/denylist rules</li>
 *   <li>{@code LoopDetector} — checks for repeated calls</li>
 *   <li>{@code InjectionGuard} — scans arguments for injection patterns</li>
 * </ul>
 *
 * <p>Guards are composable — a {@code DefaultAgentGuard} chains multiple
 * {@code ToolGuard} instances in order, stopping at the first BLOCKED result.
 */
@FunctionalInterface
public interface ToolGuard {

    /**
     * Evaluates the given tool call and returns a decision.
     *
     * @param toolCall the tool invocation to evaluate
     * @return a {@link GuardResult} — never null
     * @throws RuntimeException if the guard itself fails (handled by fail-safe logic)
     */
    GuardResult evaluate(ToolCall toolCall);
}
