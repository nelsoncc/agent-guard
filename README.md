# 🛡️ Agent Guard

![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)
![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-green)
![Status: Pre-release](https://img.shields.io/badge/Status-Pre--release-orange)
![Focus: Runtime Governance](https://img.shields.io/badge/Focus-Runtime%20Governance-black)

**Runtime governance for tool-using AI agents in Java.**

Agent Guard is a Java-native SDK that sits **between your agent runtime and the tools it wants to call**. It helps teams
enforce **budget limits, tool authorization, human approval, loop brakes, and defense-in-depth prompt-injection checks**
before risky actions execute.

It is designed for teams that already know how to build agents with **LangChain4j, Quarkus, Spring, or custom Java
runtimes** — and now need a deterministic control layer for what those agents are allowed to do.

---

## 🎯 Why Agent Guard?

Most frameworks help you **build** agents.
Most observability platforms help you **see** what agents did.
Most cloud safety products help you **filter** prompts and responses.

What is still less standardized in Java is a lightweight SDK that can **stop**, **gate**, or **pause** agent actions
**inside your application runtime** before a dangerous tool call happens.

That is the problem Agent Guard is built to solve.

```text
Your app -> Agent Guard -> Agent runtime -> LLM / Tools / MCP servers
                |
                +-> budget enforcement
                +-> tool authorization
                +-> human approval
                +-> loop braking
                +-> injection scanning
                +-> OTel export
```

---

## ⚡ What it gives you

### Budget firewall

Set hard limits on token and cost usage and stop runs before they become expensive or unstable.

Examples:

- max cost per run
- max cost per hour
- max tokens per run
- scoped budgets per workspace or user

### Tool authorization

Evaluate tool calls before execution and decide whether they should be allowed, blocked, or approval-gated.

Examples:

- allow read-only tools by default
- block destructive tools completely
- require approval for outbound messaging or admin actions
- fail closed when policy evaluation cannot complete

### Human approval for sensitive actions

Pause execution and wait for an explicit yes/no decision when a tool is too risky to run automatically.

Examples:

- `send_email`
- `post_to_slack`
- `export_customer_data`
- `delete_resource`

### Loop brakes

Interrupt repeated tool-call patterns before an agent burns budget or gets stuck in unproductive cycles.

Examples:

- repeated search with equivalent inputs
- the same tool called again and again without progress
- backoff before hard interrupt

### Defense in depth for prompt injection

Scan suspicious inputs and tool arguments for known prompt-attack and jailbreak patterns before downstream execution.

Examples:

- "ignore previous instructions"
- tool arguments containing hidden instructions
- suspicious exfiltration or override patterns

### OpenTelemetry-compatible export

Integrate policy outcomes, traces, and metrics with the telemetry stack you already use.

Examples:

- Grafana
- Datadog
- Honeycomb
- any OTel-compatible backend

---

## 🚫 What Agent Guard is **not**

Agent Guard is **not**:

- a replacement for **LangChain4j**, **Spring AI**, or **Quarkus AI** integrations
- a hosted observability platform like **Langfuse**, **Helicone**, or **Phoenix**
- a cloud safety product like **Bedrock Guardrails**, **Prompt Shields**, or **Vertex safety filters**
- a full orchestration framework for planning, memory, or multi-agent workflows

Agent Guard works best as a **governance layer on top of those systems**, not as a substitute for them.

---

## 🚀 Quick start

### 1) Add the BOM

> Replace `0.1.0-SNAPSHOT` with your current version or release tag.

```xml

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.agentguard</groupId>
            <artifactId>agent-guard-bom</artifactId>
            <version>0.1.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 2) Add the modules you need

```xml

<dependencies>
    <dependency>
        <groupId>io.agentguard</groupId>
        <artifactId>agent-guard-runtime</artifactId>
    </dependency>

    <dependency>
        <groupId>io.agentguard</groupId>
        <artifactId>agent-guard-langchain4j</artifactId>
    </dependency>

    <dependency>
        <groupId>io.agentguard</groupId>
        <artifactId>agent-guard-otel</artifactId>
    </dependency>
</dependencies>
```

### 3) Build a guard

```java
import io.agentguard.core.AgentGuard;
import io.agentguard.core.GuardResult;
import io.agentguard.core.ToolCall;
import io.agentguard.core.policy.BudgetPolicy;
import io.agentguard.core.policy.InjectionGuardPolicy;
import io.agentguard.core.policy.LoopPolicy;
import io.agentguard.core.policy.ToolPolicy;

import java.math.BigDecimal;
import java.util.Map;

AgentGuard guard = AgentGuard.builder()
        .budget(BudgetPolicy.perRun(BigDecimal.valueOf(0.50)))
        .budget(BudgetPolicy.perHour(BigDecimal.valueOf(2.00)))
        .loopDetection(LoopPolicy.maxRepeats(3).withinLastNCalls(10).build())
        .toolPolicy(ToolPolicy.denyAll()
                .allow("web_search")
                .allow("read_file")
                .requireConsent("send_email")
                .deny("delete_db")
                .build())
        .injectionGuard(InjectionGuardPolicy.defaultRules())
        .build();

guard.

startRun("run-42");

GuardResult result = guard.evaluateToolCall(
        ToolCall.of("call-1", "web_search", Map.of("query", "agent governance java"))
);

if(result.

wasBlocked()){
        throw new

IllegalStateException(result.blockReason().

orElse("Blocked"));
        }

        guard.

recordTokenUsage(500,200,"gpt-4o");
```

---

## LangChain4j example

Agent Guard does **not** replace LangChain4j. It adds governance on top of it.

```java
import io.agentguard.core.AgentGuard;
import io.agentguard.core.policy.ToolPolicy;
import io.agentguard.langchain4j.GuardedAiService;

interface ResearchAgent {
    String searchWeb(String query);

    String deleteDatabase(String name);
}

ResearchAgent raw = /* your LangChain4j AiService */ null;

AgentGuard guard = AgentGuard.builder()
        .toolPolicy(ToolPolicy.denyAll()
                .allow("searchWeb")
                .deny("deleteDatabase")
                .build())
        .build();

ResearchAgent guarded = GuardedAiService.wrap(raw, ResearchAgent.class, guard);
```

Now the service keeps using LangChain4j, but dangerous calls are evaluated first.

---

## Spring Boot example

With the Spring module on the classpath, Agent Guard can be auto-configured from `application.yml`.

```yaml
agent-guard:
  enabled: true
  service-name: research-agent
  fail-safe: FAIL_CLOSED
  budget:
    per-run-usd: 0.50
    per-hour-usd: 2.00
  loop:
    max-repeats: 3
    window-size: 10
    backoff: true
  tool-policy:
    default-action: BLOCKED
    allow:
      - web_search
      - read_file
    deny:
      - delete_db
    require-consent:
      - send_email
  injection:
    enabled: true
    enforce: true
```

The project includes a Spring auto-configuration module so you can inject `AgentGuard` as a bean.

---

## Quarkus example

With the Quarkus module on the classpath, Agent Guard can be wired using config mapping and a CDI producer.

```properties
agent-guard.enabled=true
agent-guard.service-name=research-agent
agent-guard.fail-safe=FAIL_CLOSED
agent-guard.budget.per-run-usd=0.50
agent-guard.budget.per-hour-usd=2.00
agent-guard.loop.max-repeats=3
agent-guard.loop.window-size=10
agent-guard.tool-policy.default-action=BLOCKED
agent-guard.tool-policy.allow=web_search,read_file
agent-guard.tool-policy.deny=delete_db
agent-guard.tool-policy.require-consent=send_email
agent-guard.injection.enabled=true
agent-guard.injection.enforce=true
```

The project includes a Quarkus producer that builds an application-scoped `AgentGuard` bean.

---

## Policy-as-code example

Policies can be loaded from YAML or JSON.

```yaml
default: BLOCKED

allow:
  - web_search
  - read_file

deny:
  - delete_db

requireConsent:
  - send_email

risks:
  nuke_everything: CRITICAL
  write_file: HIGH
  read_file: LOW

contextRules:
  - tool: debug_tool
    action: ALLOW
    environment: DEV
  - tool: deploy
    action: REQUIRE_CONSENT
    environment: STAGING
```

This keeps runtime governance explicit, reviewable, and easy to evolve.

---

## 🗺️ Where Agent Guard fits in the 2026 market

The market is already strong in several adjacent areas:

- **LangChain4j** gives you Java-native agent building blocks and guardrail hooks.
- **Quarkus LangChain4j** adds framework-native tool guardrails, MCP support, and telemetry integration.
- **Spring AI** gives you advisors, tool-calling abstractions, and MCP/security modules.
- **OpenTelemetry** gives you telemetry standards for GenAI workloads.
- **Cloud platforms** give you managed prompt/content safety and moderation.
- **Observability platforms** give you traces, evals, and cost analytics.

Agent Guard is therefore **not** trying to be the only guardrails solution.

Its position is narrower and sharper:

> **Agent Guard focuses on inline governance for tool-using Java agents.**
>
> It gives teams a reusable way to enforce **budget limits, tool authorization, human approval, and loop brakes** inside
> their own application runtime.

---

## Why not just use LangChain4j, Spring AI, or Quarkus?

Because they solve a different layer of the problem.

| Problem                                         | Frameworks       | Agent Guard |
|-------------------------------------------------|------------------|-------------|
| Build agents and tool workflows                 | Strong           | Partial     |
| Intercept model or tool calls                   | Strong           | Strong      |
| Reusable governance semantics across frameworks | Limited          | Strong      |
| Hard budget enforcement inside the app          | Limited          | Strong      |
| Human approval for risky tools                  | Limited          | Strong      |
| Loop braking                                    | Limited          | Strong      |
| OTel export                                     | Strong           | Strong      |
| Managed safety and moderation                   | Usually external | No          |

A good way to think about it:

- **LangChain4j / Spring AI / Quarkus** help you build agentic applications
- **Agent Guard** helps you control what those agents are allowed to do at runtime

---

## 🔍 What about the governance SDKs emerging in 2026?

The runtime governance space is growing fast. Several SDKs launched in early 2026 with overlapping goals:

| Project                                                                                     | Focus                                               | Language       |
|---------------------------------------------------------------------------------------------|-----------------------------------------------------|----------------|
| [Microsoft Agent Governance Toolkit](https://github.com/microsoft/agent-governance-toolkit) | Policy enforcement, zero-trust identity, sandboxing | Python         |
| [Galileo Agent Control](https://galileo.ai/blog/announcing-agent-control)                   | Centralized control plane, pluggable evaluators     | Python         |
| [Sekuire](https://sekuire.ai/)                                                              | Runtime governance SDK, OAGS spec                   | TypeScript     |
| [APort Agent Guardrails](https://github.com/aporthq/aport-agent-guardrails)                 | Pre-action authorization, kill switch               | Node.js/Python |
| [Traccia](https://traccia.ai/)                                                              | Agent tracing, loop detection, budget control       | Python         |

These are good projects solving real problems.

Agent Guard's position relative to them is straightforward:

> **All comparable governance SDKs in 2026 are Python or TypeScript.**
> Agent Guard is the Java-native option.

If your stack is Java — and you already use LangChain4j, Spring Boot, or Quarkus — Agent Guard gives you governance
without leaving the JVM or adding a sidecar.

---

## Supported modules

### `agent-guard-core`

Core interfaces and contracts with minimal runtime assumptions.

### `agent-guard-runtime`

Default runtime implementation: budgets, loop detection, tool policy, injection checks.

### `agent-guard-otel`

OpenTelemetry and Micrometer-friendly integration.

### `agent-guard-langchain4j`

LangChain4j wrappers and integration helpers, including MCP-related guards.

### `agent-guard-spring`

Spring Boot auto-configuration and properties-based setup.

### `agent-guard-quarkus`

Quarkus configuration mapping and CDI producer.

### `agent-guard-bom`

Centralized dependency management for all modules.

---

## Use cases

Agent Guard is a good fit when your agent can:

- call internal APIs or databases
- trigger external messaging
- operate across tenants or workspaces
- spend real money through model/tool usage
- act on content retrieved from untrusted sources
- run unattended for long periods

Example scenarios:

- internal copilots with approval-gated actions
- agentic admin tools with destructive operations
- coding agents with budget and loop controls
- multi-tenant assistants with scoped policy and budget enforcement
- MCP-connected runtimes that need an extra authorization layer

---

## 📊 Honest current scope

Agent Guard is strongest when described honestly.

### Strong today

- budget enforcement inside the Java runtime
- allow/block/require-consent tool policies
- approval-gated actions
- loop detection for repeated tool-call patterns
- Spring, Quarkus, LangChain4j, and OTel integration points

### Still early / evolving

- budget persistence across JVM restarts
- richer policy backends
- more advanced semantic loop detection
- deeper managed-provider integrations
- broader production hardening and real-world examples

If you position the project as **runtime governance for tool-using Java agents**, the story is strong.
If you position it as **the all-in-one AI guardrails platform**, the story becomes much weaker.

---

## Running the project locally

```bash
mvn clean test
```

The repository includes example modules for Spring Boot and Quarkus under `examples/`.

Helpful docs:

- [`docs/COOKBOOK.md`](docs/COOKBOOK.md)
- [`docs/DECISIONS.md`](docs/DECISIONS.md)
- [`docs/CHANGELOG.md`](docs/CHANGELOG.md)
- [`docs/PUBLISHING.md`](docs/PUBLISHING.md)

---

## Suggested public positioning

### Headline

**Runtime governance for tool-using AI agents in Java**

### One-paragraph description

Agent Guard is a Java-native runtime governance SDK for AI agents that call tools. It sits on top of existing agent
frameworks and enforces budget limits, tool authorization, human approval, loop brakes, and defense-in-depth
prompt-injection checks before risky actions execute.

### Elevator pitch

If LangChain4j, Quarkus, and Spring help you **build** agents, Agent Guard helps you **control what those agents are
allowed to do**.

---

## 🤝 Contributing

Contributions are welcome.

Good contribution areas:

- provider integrations
- policy backends
- richer examples
- performance and concurrency hardening
- documentation and cookbook recipes

If you want to shape the roadmap, start by opening an issue describing the governance problem you are trying to solve.

---

## License

Apache 2.0. See [`LICENSE`](LICENSE).
