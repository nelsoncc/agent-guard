# Changelog

All notable changes to Agent Guard are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.1.0] — 2026-03-18

### 🎉 Initial release

First public release of Agent Guard, a runtime governance library for AI agents.

---

### ✅ Added

#### Core interfaces (`agent-guard-core`)

- `AgentGuard` - main interface with fluent builder and `ServiceLoader`-based factory
  discovery; zero external dependencies
- `GuardResult` - immutable evaluation result with `status()`, `violation()`,
  `blockReason()`, `toolName()`, `tokensConsumed()`, `estimatedCost()`
- `GuardStatus` - `ALLOWED` | `BLOCKED` | `REQUIRE_CONSENT`
- `ViolationType` - typed violation categories: `BUDGET_EXCEEDED`, `LOOP_DETECTED`,
  `TOOL_DENIED`, `TOOL_REQUIRES_CONSENT`, `PROMPT_INJECTION`, `DATA_EXFILTRATION`,
  `INTERNAL_GUARD_ERROR`
- `ToolCall` - immutable tool invocation with id, name, arguments, rawInput, and
  optional `ExecutionContext`
- Policy value objects: `BudgetPolicy`, `ToolPolicy`, `LoopPolicy`,
  `InjectionGuardPolicy`, `ExecutionContext`, `FailSafeMode`, `ToolRisk`
- SPI interfaces: `ToolGuard`, `ConsentHandler`, `TokenUsageReporter`, `Resettable`
- Typed exceptions: `BudgetExceededException`, `LoopDetectedException`,
  `ToolDeniedException`, `PromptInjectionException`

#### Budget Firewall (`agent-guard-runtime`)

- `BudgetFirewall` - thread-safe per-run and rolling-window (hourly/daily) cost and
  token enforcement; supports multiple simultaneous policies
- `TokenCostTable` - built-in pricing for 20+ models: OpenAI (gpt-4o, gpt-4o-mini,
  gpt-4-turbo, gpt-3.5-turbo), Anthropic (claude-3-5-sonnet/haiku/opus, claude-3-*),
  Google (gemini-1.5-pro/flash, gemini-2.0-flash), Ollama (free)
- Multi-tenant: workspace-scoped and user-scoped budget policies
- `BudgetExceededException` thrown on limit exhaustion (fail-closed)

#### Loop Detector (`agent-guard-runtime`)

- `LoopDetector` - sliding-window exact and semantic repetition detection
- `CallSignature` - exact key (byte-identical) and semantic key (normalised)
  fingerprinting
- Configurable backoff: warn once at `maxRepeats-1`, block at `maxRepeats`
- Implements `Resettable` - state cleared by `AgentGuard.startRun()`

#### Tool Policy Engine (`agent-guard-runtime`)

- `ToolPolicyEngine` - enforces allowlist/denylist/consent/risk rules
- Per-tool risk scoring: `CRITICAL` → BLOCKED, `HIGH` → REQUIRE_CONSENT
- Async consent flow with `ConsentHandler` (timeout, interrupt, exception all fail-closed)
- Context-aware rules via `ExecutionContext` (environment, userId, workspaceId, tags)
- `PolicyFileLoader` - load `ToolPolicy` from YAML or JSON (policy-as-code)
- Bundled example policy: `agent-guard-policy.yaml`

#### Injection Guard (`agent-guard-runtime`)

- `InjectionGuard` - scans `rawInput` and all argument values against compiled patterns
- 7 built-in default rules:
    - Ignore-instructions injection (ignore/disregard/forget/override/bypass)
    - Role-confusion / act-as / pretend
    - Jailbreak keywords (DAN, STAN, "do anything now", developer mode)
    - Embedded system-instruction tags (`[SYSTEM]`, `[INST]`, `<<SYS>>`, `<s>`)
    - `mailto:` data-exfiltration URIs
    - Suspicious email addresses in tool arguments
    - External HTTP/HTTPS URLs in tool arguments
- Enforce mode: throws `PromptInjectionException`; audit mode: logs and allows
- Custom rules via `InjectionGuardPolicy.builder().addRule(...)`
- Regex patterns compiled eagerly at construction time (fail-fast)

#### OTel Observability (`agent-guard-otel`)

- `OtelAgentGuard` - decorator wrapping any `AgentGuard` with tracing and metrics
- `GenAiTracer` - OTel spans using `gen_ai.*` semantic conventions:
  `gen_ai.agent.run`, `gen_ai.tool.call`; attributes include
  `gen_ai.tool.name`, `gen_ai.tool.call.id`, `gen_ai.usage.input_tokens`,
  `gen_ai.usage.output_tokens`, `gen_ai.request.model`,
  `gen_ai.agent.guard.block_reason`, `gen_ai.agent.guard.budget_remaining`,
  `gen_ai.agent.guard.cost_usd`, `gen_ai.agent.guard.violation_type`
- `TokenMeter` - Micrometer metrics: `agent.guard.tool_calls.total`,
  `agent.guard.blocks.total`, `agent.guard.tokens.input`,
  `agent.guard.tokens.output`, `agent.guard.cost.usd`,
  `agent.guard.tool_call.latency`, `agent.guard.budget.remaining`
- Both OTel and Micrometer are optional at runtime - falls back to no-op
- Exportable Grafana dashboard JSON with 13 panels

#### LangChain4j Integration (`agent-guard-langchain4j`)

- `GuardedAiService` - Java dynamic proxy wrapping any LangChain4j `AiService`
  interface; reads `@Tool` annotation names via reflection
- `McpServerGuard` - guards MCP tool executors via
  `Function<Map<String,Object>, Object>` contract; framework-agnostic

#### Spring Boot Integration (`agent-guard-spring`)

- `AgentGuardProperties` - `@ConfigurationProperties(prefix = "agent-guard")` binding
- `AgentGuardBuilderFactory` - pure-Java properties-to-builder translation
- `AgentGuardAutoConfiguration` - `@Bean @ConditionalOnMissingBean` for `AgentGuard`
- Registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

#### Quarkus Extension (`agent-guard-quarkus`)

- `AgentGuardConfig` - `@ConfigMapping(prefix = "agent-guard")` interface with nested
  config groups for budget, loop, tool policy, and injection
- `AgentGuardProducer` - CDI `@ApplicationScoped` `@Produces @DefaultBean` producer
- GraalVM native-image compatible (no reflection on user classes)

#### Examples

- `examples/spring-boot-example` - demonstrates all three integration patterns:
  properties-driven auto-config, programmatic builder, `GuardedAiService`
- `examples/quarkus-example` - demonstrates CDI producer wiring and MCP guard

#### Documentation

- `docs/COOKBOOK.md` - 12 sections with 30+ practical recipes
- `docs/CONTEXT.md` - full implementation state for AI-assisted development
- `docs/PUBLISHING.md` - Maven Central release process
- `docs/CHANGELOG.md` - this file
- Package-level Javadoc for all public packages in `agent-guard-core` and
  `agent-guard-runtime`

#### Testing

- 319 `@Test` methods across 15 test classes
- All modules compile against JDK 17 and 21 with zero errors
- Test classes: `GuardResultTest`, `GuardStatusTest`, `ToolCallTest`,
  `BudgetPolicyTest`, `LoopPolicyTest`, `ToolPolicyTest`,
  `DefaultAgentGuardTest`, `BudgetFirewallTest`, `LoopDetectorTest`,
  `ToolPolicyEngineTest`, `InjectionGuardTest`, `OtelAgentGuardTest`,
  `LangChain4jIntegrationTest`, `SpringAutoConfigTest`, `QuarkusProducerTest`

#### Infrastructure

- Maven multi-module build with BOM (`agent-guard-bom`)
- GitHub Actions CI: Java 17 + 21 matrix, JaCoCo coverage, Maven Central release job
- Dependabot: weekly Maven + GitHub Actions scans with grouped PRs

---

### 🏗️ Architecture decisions

| # | Decision                                                                                                   |
|---|------------------------------------------------------------------------------------------------------------|
| 1 | Java as primary language - differentiation from Python/TS ecosystem                                        |
| 2 | Maven multi-module with BOM - modular adoption without version conflicts                                   |
| 3 | LangChain4j as primary integration target                                                                  |
| 4 | `Resettable` SPI for per-run state - clean reset without tight coupling                                    |
| 5 | `ServiceLoader` for factory discovery - `agent-guard-core` stays dep-free                                  |
| 6 | Reflection for optional deps (OTel, Micrometer, LangChain4j, Spring) - compile without JARs                |
| 7 | `ContextRule` as record in `ToolPolicy` - immutable, no extra file                                         |
| 8 | `BudgetFirewall` throws exception (not returns blocked result) - ensures budget is never silently bypassed |

---

#### LangChain4j native guardrails (`agent-guard-langchain4j`)

- `AgentGuardInputGuardrail` - implements LangChain4j `InputGuardrail` interface;
  scans user messages for injection/jailbreak before reaching the LLM
- `AgentGuardOutputGuardrail` - implements LangChain4j `OutputGuardrail` interface;
  scans AI responses for injection patterns and data exfiltration
- `AgentGuardRequestTransformer` - implements `UnaryOperator<ChatRequest>` for
  LangChain4j's `chatRequestTransformer` hook; intercepts requests before the LLM
- These enable Agent Guard as a **native LangChain4j guardrail** - zero-boilerplate
  integration via `@InputGuardrails` / `@OutputGuardrails` annotations on AiServices
- `langchain4j-core` added as `optional` compile dependency (no longer reflection-only)

#### Spring Boot 4 auto-configuration (`agent-guard-spring`)

- `AgentGuardAutoConfiguration` uses `@AutoConfiguration`, `@ConditionalOnClass`,
  `@ConditionalOnProperty`, `@ConditionalOnMissingBean` annotations
- `AgentGuardProperties` annotated with `@ConfigurationProperties(prefix = "agent-guard")`
- Follows Spring Boot 4 modular auto-configuration pattern
- Registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

#### Dependency versions

| Dependency   | Version      |
|--------------|--------------|
| Java         | 21 (minimum) |
| Spring Boot  | 4.0.3        |
| LangChain4j  | 1.12.2       |
| Quarkus      | 3.27.2 (LTS) |
| Jackson      | 2.21.1       |
| Micrometer   | 1.16.4       |
| Resilience4j | 2.3.0        |
| SLF4J        | 2.0.17       |
| JUnit        | 5.12.0       |
| AssertJ      | 3.27.7       |
| Mockito      | 5.23.0       |

Compiler plugin uses `--release 21` instead of `-source/-target`.

---

## [Unreleased]

### Planned for 0.2.0

- Resilience4j circuit breaker integration (Issue TBD)
- Budget persistence across JVM restarts (Redis/DB backend)
- Semantic similarity via embedding vectors for loop detection
- `agent-guard-mcp-server` - standalone MCP server exposing guard as a service
- Rate limiting per tool (calls/minute)

---

[0.1.0]: https://github.com/agentguard/agent-guard/releases/tag/v0.1.0
