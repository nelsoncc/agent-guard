/**
 * Service Provider Interfaces (SPIs) for extending Agent Guard.
 *
 * <p>These interfaces define the extension points that allow third-party code
 * to plug into the guard pipeline without modifying the core library.
 *
 * <ul>
 *   <li>{@link io.agentguard.core.spi.ToolGuard} — the fundamental guard unit; a single
 *       {@code evaluate(ToolCall)} method. Multiple guards are chained in
 *       {@code DefaultAgentGuard}. Implement this to add custom policies.</li>
 *   <li>{@link io.agentguard.core.spi.ConsentHandler} — asynchronous human-approval flow
 *       for {@code REQUIRE_CONSENT} tool calls. Implementations can use Slack, email,
 *       a UI dialog, or any other mechanism.</li>
 *   <li>{@link io.agentguard.core.spi.TokenUsageReporter} — reports token consumption
 *       to the budget tracking layer after each model response.</li>
 *   <li>{@link io.agentguard.core.spi.Resettable} — guards that hold per-run state
 *       (e.g. {@code BudgetFirewall}, {@code LoopDetector}) implement this to be
 *       reset at the start of each new agent run via
 *       {@link io.agentguard.core.AgentGuard#startRun(String)}.</li>
 * </ul>
 */
package io.agentguard.core.spi;
