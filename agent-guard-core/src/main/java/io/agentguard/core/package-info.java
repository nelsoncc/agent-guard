/**
 * Agent Guard — runtime governance layer for AI agents.
 *
 * <h2>Overview</h2>
 * <p>Agent Guard is a lightweight, open-source library that adds a governance
 * layer to any AI agent, regardless of the framework used (LangChain4j, Spring AI,
 * custom). It provides:
 *
 * <ul>
 *   <li><b>Budget Firewall</b> — per-run, per-hour, and per-day cost and token limits
 *       with an automatic kill-switch ({@link io.agentguard.core.exception.BudgetExceededException}).</li>
 *   <li><b>Loop Detector</b> — detects exact and semantic repetition in tool calls
 *       with a configurable sliding window and backoff strategy.</li>
 *   <li><b>Tool Policy Engine</b> — allowlist/denylist/consent rules, per-tool risk
 *       scoring, context-aware environment overrides, and policy-as-code YAML/JSON loading.</li>
 *   <li><b>Injection Guard</b> — scans tool inputs for prompt injection patterns including
 *       ignore-instructions, role confusion, jailbreak keywords, and data exfiltration attempts.</li>
 *   <li><b>OTel Observability</b> — OpenTelemetry spans with {@code gen_ai.*} semantic
 *       conventions and Micrometer metrics for Prometheus/Grafana.</li>
 * </ul>
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * AgentGuard guard = AgentGuard.builder()
 *     .budget(BudgetPolicy.perHour(BigDecimal.valueOf(2.00)))
 *     .loopDetection(LoopPolicy.maxRepeats(3).withinLastNCalls(10).build())
 *     .toolPolicy(ToolPolicy.denyAll()
 *         .allow("web_search")
 *         .allow("read_file")
 *         .requireConsent("send_email")
 *         .build())
 *     .injectionGuard(InjectionGuardPolicy.defaultRules())
 *     .build();
 *
 * guard.startRun("run-001");
 * GuardResult result = guard.evaluateToolCall(toolCall);
 *
 * if (result.wasBlocked()) {
 *     log.warn("Blocked: {}", result.blockReason().orElse("unknown"));
 * }
 * }</pre>
 *
 * <h2>Module structure</h2>
 * <ul>
 *   <li>{@code agent-guard-core} — interfaces and contracts (this module, no external dependencies)</li>
 *   <li>{@code agent-guard-runtime} — runtime implementations (BudgetFirewall, LoopDetector, etc.)</li>
 *   <li>{@code agent-guard-otel} — OpenTelemetry and Micrometer integration</li>
 *   <li>{@code agent-guard-langchain4j} — LangChain4j AI service and MCP integration</li>
 *   <li>{@code agent-guard-spring} — Spring Boot auto-configuration</li>
 *   <li>{@code agent-guard-quarkus} — Quarkus CDI extension</li>
 *   <li>{@code agent-guard-bom} — Bill of Materials for consistent version management</li>
 * </ul>
 *
 * @see io.agentguard.core.AgentGuard
 * @see io.agentguard.core.GuardResult
 * @see io.agentguard.core.policy.BudgetPolicy
 * @see io.agentguard.core.policy.ToolPolicy
 */
package io.agentguard.core;
