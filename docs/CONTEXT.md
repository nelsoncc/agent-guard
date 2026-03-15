# CONTEXT.md — Agent Guard Implementation Details

> **Auto-maintained by the AI.** Updated at the end of every development session.
> Last updated: **2026-03-18** | Session: **Dependency upgrade + LangChain4j 1.x guardrails + Spring Boot 4 auto-config
**

---

## Session log

| Session       | Milestone                                            | Issues  | Status |
|---------------|------------------------------------------------------|---------|--------|
| 2026-03-14 #1 | Milestone 0 - Scaffolding                            | #1–#4   | ✅      |
| 2026-03-14 #2 | Milestone 1 - Budget Firewall                        | #5–#10  | ✅      |
| 2026-03-14 #3 | Milestone 2 - Loop Detector                          | #11–#15 | ✅      |
| 2026-03-14 #4 | Milestone 3 - Tool Policy Engine                     | #16–#21 | ✅      |
| 2026-03-14 #5 | Milestone 1 - Budget Firewall (fix)                  | #5–#10  | ✅      |
| 2026-03-14 #6 | Milestone 4 - Injection Guard                        | #22–#27 | ✅      |
| 2026-03-14 #7 | Milestone 5 - OTel Observability                     | #28–#33 | ✅      |
| 2026-03-14 #8 | Milestone 6 - Integrations                           | #34–#39 | ✅      |
| 2026-03-14 #9 | Milestone 7 - Publication                            | #40–#43 | ✅      |
| 2026-03-17    | Post-M7 - Quarkus tests + docs cleanup               | —       | ✅      |
| 2026-03-18    | Dep upgrade + LangChain4j guardrails + Spring Boot 4 | —       | ✅      |

---

## Module inventory

| Module                    | Status     | Source classes | Test classes | Notes                                                                                    |
|---------------------------|------------|----------------|--------------|------------------------------------------------------------------------------------------|
| `agent-guard-bom`         | ✅ Complete | 0 (POM only)   | 0            | BOM for all 6 library modules                                                            |
| `agent-guard-core`        | ✅ Complete | 14             | 6            | Interfaces, policies, exceptions, SPI                                                    |
| `agent-guard-runtime`     | ✅ Complete | 10             | 5            | BudgetFirewall, LoopDetector, ToolPolicyEngine, InjectionGuard                           |
| `agent-guard-otel`        | ✅ Complete | 6              | 1            | GenAiTracer, TokenMeter, OtelAgentGuard                                                  |
| `agent-guard-langchain4j` | ✅ Complete | 5              | 2            | GuardedAiService, McpServerGuard, InputGuardrail, OutputGuardrail, RequestTransformer    |
| `agent-guard-spring`      | ✅ Complete | 3              | 1            | @AutoConfiguration + @ConditionalOnMissingBean, BuilderFactory, @ConfigurationProperties |
| `agent-guard-quarkus`     | ✅ Complete | 2              | 1            | AgentGuardConfig, AgentGuardProducer                                                     |

---

## Full file map - agent-guard-core

```
src/main/java/io/agentguard/core/
├── AgentGuard.java               # Builder: consentTimeoutSeconds(long) added M3
├── AgentGuardFactory.java
├── GuardResult.java
├── GuardStatus.java
├── ToolCall.java                 # M3: context(ExecutionContext) field + builder method
├── ViolationType.java
│
├── policy/
│   ├── BudgetPolicy.java
│   ├── ExecutionContext.java     # M3: environment, userId, workspaceId, tags
│   ├── FailSafeMode.java
│   ├── InjectionGuardPolicy.java
│   ├── LoopPolicy.java
│   ├── ToolPolicy.java           # M3: ContextRule record, evaluate(name,ctx)
│   └── ToolRisk.java
│
├── spi/
│   ├── ConsentHandler.java
│   ├── Resettable.java
│   ├── ToolGuard.java
│   └── TokenUsageReporter.java
│
└── exception/
    ├── AgentGuardException.java
    ├── BudgetExceededException.java
    ├── LoopDetectedException.java
    ├── PromptInjectionException.java
    └── ToolDeniedException.java
```

## Full file map - agent-guard-runtime

```
src/main/java/io/agentguard/runtime/
├── CallSignature.java             # Used by LoopDetector (M2)
├── DefaultAgentGuard.java
├── DefaultAgentGuardFactory.java
├── LoopDetector.java              # M2: sliding-window exact + semantic detection
├── BudgetFirewall.java            # M1: rolling-window + per-run cost/token enforcement
├── TokenCostTable.java            # M1: provider pricing + token→USD calculation
├── TokenUsage.java                # M1: timestamped usage observation for rolling windows
├── InjectionGuard.java            # M4: 7 default rules, enforce + audit mode
├── PolicyFileLoader.java          # M3: YAML/JSON policy-as-code
└── ToolPolicyEngine.java          # M3: allowlist/denylist/consent/risk rules

src/main/resources/
├── META-INF/services/io.agentguard.core.AgentGuardFactory
└── agent-guard-policy.yaml        # Example policy with all features

src/test/java/io/agentguard/runtime/
├── DefaultAgentGuardTest.java
├── BudgetFirewallTest.java        # 35+ tests across 7 nested classes (M1)
├── InjectionGuardTest.java        # 36+ tests across 9 nested classes (M4)
├── LoopDetectorTest.java          # 20+ tests (M2)
└── ToolPolicyEngineTest.java      # 45+ tests across 6 nested classes (M3)

src/test/resources/
├── test-policy.yaml               # YAML fixture for PolicyFileLoader tests
└── test-policy.json               # JSON fixture for PolicyFileLoader tests
```

## Full file map - agent-guard-otel

```
src/main/java/io/agentguard/otel/
├── GenAiTracer.java               # OTel spans with gen_ai.* conventions
├── TokenMeter.java                # Micrometer metrics
├── OtelAgentGuard.java            # Decorator wrapping AgentGuard with tracing + metrics
└── (3 other supporting classes)

src/main/resources/
└── dashboards/agent-guard-grafana.json  # Exportable Grafana dashboard (13 panels)

src/test/java/io/agentguard/otel/
└── OtelAgentGuardTest.java        # 37 tests
```

## Full file map - agent-guard-langchain4j

```
src/main/java/io/agentguard/langchain4j/
├── AgentGuardInputGuardrail.java     # Native LangChain4j InputGuardrail implementation
├── AgentGuardOutputGuardrail.java    # Native LangChain4j OutputGuardrail implementation
├── AgentGuardRequestTransformer.java # chatRequestTransformer for LangChain4j AiServices
├── GuardedAiService.java             # Java dynamic proxy wrapping LangChain4j AiService
└── McpServerGuard.java               # Guards MCP tool executors

src/test/java/io/agentguard/langchain4j/
├── GuardrailIntegrationTest.java   # 10 tests (input, output, transformer)
└── LangChain4jIntegrationTest.java # 12 tests (proxy, MCP)
```

## Full file map - agent-guard-spring

```
src/main/java/io/agentguard/spring/
├── AgentGuardAutoConfiguration.java  # Spring Boot auto-config
├── AgentGuardBuilderFactory.java     # Pure-Java properties → builder translation
└── AgentGuardProperties.java         # @ConfigurationProperties binding

src/main/resources/
└── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

src/test/java/io/agentguard/spring/
└── SpringAutoConfigTest.java      # 14 tests (defaults, builder factory, auto-config)
```

## Full file map - agent-guard-quarkus

```
src/main/java/io/agentguard/quarkus/
├── AgentGuardConfig.java          # Quarkus @ConfigMapping interface
└── AgentGuardProducer.java        # CDI @Produces bean producer

src/test/java/io/agentguard/quarkus/
└── QuarkusProducerTest.java       # 19 tests (config defaults, producer wiring, all features)
```

## Full file map - examples

```
examples/spring-boot-example/
└── src/main/java/io/agentguard/example/spring/
    └── AgentGuardSpringExample.java

examples/quarkus-example/
└── src/main/java/io/agentguard/example/quarkus/
    └── AgentGuardQuarkusExample.java
```

---

## Test coverage summary

| Module              | Test class                 | Tests   | Milestone |
|---------------------|----------------------------|---------|-----------|
| agent-guard-core    | GuardResultTest            | 8       | M0        |
| agent-guard-core    | GuardStatusTest            | 6       | M0        |
| agent-guard-core    | ToolCallTest               | 6       | M0        |
| agent-guard-core    | BudgetPolicyTest           | 10      | M0        |
| agent-guard-core    | LoopPolicyTest             | 5       | M0        |
| agent-guard-core    | ToolPolicyTest             | 11      | M0        |
| agent-guard-runtime | DefaultAgentGuardTest      | *       | M0        |
| agent-guard-runtime | BudgetFirewallTest         | *       | M1        |
| agent-guard-runtime | LoopDetectorTest           | *       | M2        |
| agent-guard-runtime | ToolPolicyEngineTest       | *       | M3        |
| agent-guard-runtime | InjectionGuardTest         | *       | M4        |
| agent-guard-otel    | OtelAgentGuardTest         | 37      | M5        |
| agent-guard-lc4j    | LangChain4jIntegrationTest | 12      | M6        |
| agent-guard-lc4j    | GuardrailIntegrationTest   | 10      | Post-M7   |
| agent-guard-spring  | SpringAutoConfigTest       | 14      | M6        |
| agent-guard-quarkus | QuarkusProducerTest        | 19      | M6        |
|                     | **Total**                  | **329** |           |

\* Runtime module test counts vary by nested class; total for module = 191.

All tests compile and pass against JDK 21 (Java 21 minimum since v0.2.0).

---

## Design decisions (summary)

Full ADRs are in `docs/DECISIONS.md`. Quick reference:

| #     | Decision                                     | Milestone |
|-------|----------------------------------------------|-----------|
| D-001 | Java as primary language                     | M0        |
| D-002 | Maven multi-module with BOM                  | M0        |
| D-003 | LangChain4j as primary integration           | M0        |
| D-004 | Resilience4j for circuit breakers            | M0        |
| D-005 | Micrometer for metrics                       | M0        |
| D-006 | README as context prompt                     | M0        |
| D-007 | ContextRule as record inside ToolPolicy      | M3        |
| D-008 | Context rules evaluated before base rules    | M3        |
| D-009 | PolicyFileLoader uses reflection for Jackson | M3        |
| D-010 | Consent handler failure always fails closed  | M3        |
| D-011 | ToolPolicyEngine separate from ToolPolicy    | M3        |
| D-012 | Resettable SPI for per-run state reset       | M3        |

---

## Dependency versions (as of 2026-03-18)

| Dependency   | Version      |
|--------------|--------------|
| Java         | 21           |
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

*End of CONTEXT.md - updated by AI session 2026-03-18*
