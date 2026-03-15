/**
 * Immutable policy value objects that configure Agent Guard's behaviour.
 *
 * <p>All classes in this package are <strong>immutable and thread-safe</strong>
 * after construction. They carry no external dependencies — only {@code java.*} types.
 *
 * <h2>Key classes</h2>
 * <ul>
 *   <li>{@link io.agentguard.core.policy.BudgetPolicy} — cost and token limits with optional
 *       rolling-window (per-hour, per-day) and workspace/user scoping.</li>
 *   <li>{@link io.agentguard.core.policy.ToolPolicy} — allowlist, denylist, consent rules,
 *       risk scoring, and environment-specific context overrides.</li>
 *   <li>{@link io.agentguard.core.policy.LoopPolicy} — sliding-window loop detection configuration
 *       including exact repetition, semantic detection, and backoff strategy.</li>
 *   <li>{@link io.agentguard.core.policy.InjectionGuardPolicy} — prompt injection detection rules
 *       in enforce or audit mode, with extensible custom rules.</li>
 *   <li>{@link io.agentguard.core.policy.ExecutionContext} — runtime context (environment, userId,
 *       workspaceId, tags) for context-aware policy evaluation.</li>
 *   <li>{@link io.agentguard.core.policy.FailSafeMode} — controls guard behaviour on internal errors:
 *       {@code FAIL_CLOSED} (block, default) or {@code FAIL_OPEN} (allow, dev only).</li>
 * </ul>
 */
package io.agentguard.core.policy;
