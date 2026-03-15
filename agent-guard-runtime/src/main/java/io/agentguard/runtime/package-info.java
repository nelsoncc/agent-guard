/**
 * Runtime implementations of the Agent Guard policies.
 *
 * <p>This package contains the concrete guard components wired together by
 * {@link io.agentguard.runtime.DefaultAgentGuard} via the
 * {@link io.agentguard.core.AgentGuardFactory} ServiceLoader mechanism.
 *
 * <h2>Guard chain (evaluation order)</h2>
 * <ol>
 *   <li>{@link io.agentguard.runtime.InjectionGuard} — scans tool inputs for prompt
 *       injection patterns before any other check consumes budget.</li>
 *   <li>{@link io.agentguard.runtime.BudgetFirewall} — enforces cost and token limits;
 *       supports per-run and rolling-window (per-hour, per-day) policies simultaneously.</li>
 *   <li>{@link io.agentguard.runtime.LoopDetector} — detects exact and semantic tool-call
 *       repetition within a configurable sliding window.</li>
 *   <li>{@link io.agentguard.runtime.ToolPolicyEngine} — evaluates allowlist, denylist,
 *       and consent rules including context-aware environment overrides.</li>
 * </ol>
 *
 * <h2>Supporting classes</h2>
 * <ul>
 *   <li>{@link io.agentguard.runtime.TokenCostTable} — maps model identifiers to per-token
 *       USD prices for 20+ built-in models across OpenAI, Anthropic, Google, and Ollama.</li>
 *   <li>{@link io.agentguard.runtime.PolicyFileLoader} — loads {@code ToolPolicy} from
 *       YAML or JSON files (policy-as-code).</li>
 *   <li>{@link io.agentguard.runtime.CallSignature} — fingerprints tool calls for
 *       exact and semantic loop detection.</li>
 * </ul>
 */
package io.agentguard.runtime;
