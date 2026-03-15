/**
 * Typed exceptions thrown when Agent Guard blocks an agent action.
 *
 * <p>All exceptions extend {@link io.agentguard.core.exception.AgentGuardException},
 * which carries the {@link io.agentguard.core.GuardResult} that caused the block.
 * Callers can catch the base class to handle any guard violation, or catch specific
 * subclasses to distinguish between violation types.
 *
 * <pre>{@code
 * try {
 *     guard.evaluateToolCall(toolCall);
 * } catch (BudgetExceededException e) {
 *     log.warn("Over budget: consumed={} limit={}", e.consumed(), e.limit());
 * } catch (LoopDetectedException e) {
 *     log.warn("Loop: tool='{}' called {}x", e.repeatedToolName(), e.repeatCount());
 * } catch (PromptInjectionException e) {
 *     log.warn("Injection: rule='{}'", e.ruleDescription());
 * } catch (AgentGuardException e) {
 *     log.warn("Guard blocked: {}", e.guardResult().blockReason().orElse("unknown"));
 * }
 * }</pre>
 *
 * <h2>Exception hierarchy</h2>
 * <ul>
 *   <li>{@link io.agentguard.core.exception.AgentGuardException} (base)</li>
 *   <li>└─ {@link io.agentguard.core.exception.BudgetExceededException} — cost or token limit hit</li>
 *   <li>└─ {@link io.agentguard.core.exception.LoopDetectedException} — tool repeated too many times</li>
 *   <li>└─ {@link io.agentguard.core.exception.ToolDeniedException} — tool on denylist</li>
 *   <li>└─ {@link io.agentguard.core.exception.PromptInjectionException} — injection pattern detected</li>
 * </ul>
 */
package io.agentguard.core.exception;
