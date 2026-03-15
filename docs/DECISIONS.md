# DECISIONS.md — Architectural Decision Records

> Formal ADRs for Agent Guard.
> See also the `## 📜 Decision Log` table in `README.md` for the running summary.

---

## ADR-001: Java as primary language

**Status:** Accepted
**Date:** 2026-03
**Context:** The AI agent governance space is dominated by Python (Langfuse, Guardrails AI) and TypeScript (Helicone)
libraries. The Java/JVM ecosystem, widely used in enterprise, has no equivalent lightweight runtime governance
library.
**Decision:** Build Agent Guard in Java 17+ to serve the enterprise Java ecosystem (Spring Boot, Quarkus, Micronaut).
**Consequences:** Differentiates the project; limits initial adoption to JVM teams; LangChain4j provides a mature LLM
integration layer.

---

## ADR-002: Maven multi-module with BOM

**Status:** Accepted
**Date:** 2026-03
**Context:** Users should be able to adopt only the modules they need (e.g., just `agent-guard-runtime` without OTel or
Spring).
**Decision:** Use a Maven multi-module project with a Bill of Materials (`agent-guard-bom`) to centralise versions and
allow selective dependency adoption.
**Consequences:** Standard pattern in Java enterprise; slightly more complex build setup; BOM enables
`<scope>import</scope>` for downstream projects.

---

## ADR-003: LangChain4j as primary integration target

**Status:** Accepted
**Date:** 2026-03
**Context:** LangChain4j is the most active Java framework for LLM orchestration in 2026, with built-in MCP support and
growing community adoption.
**Decision:** Build the first integration module for LangChain4j (`GuardedAiService`, `McpServerGuard`). Keep the core
framework-agnostic so Spring AI and other integrations can follow.
**Consequences:** Fastest path to adoption; core remains independent via SPI interfaces.

---

## ADR-004: Resilience4j for circuit breakers (planned)

**Status:** Accepted (deferred to v0.2.0)
**Date:** 2026-03
**Context:** Circuit breaker patterns (trip after N failures, half-open recovery) complement budget firewalls for
production resilience.
**Decision:** Use Resilience4j as the circuit breaker library. Defer actual integration to v0.2.0 to keep v0.1.0
focused.
**Consequences:** Dependency declared but not yet wired into the guard chain.

---

## ADR-005: Micrometer for metrics

**Status:** Accepted
**Date:** 2026-03
**Context:** Teams use different metrics backends (Prometheus, Datadog, CloudWatch). Micrometer is the universal bridge
in the JVM ecosystem.
**Decision:** Use Micrometer for all metrics emission. OTel export happens via `micrometer-tracing-bridge-otel`.
**Consequences:** One codebase, N backends; familiar API for Spring/Quarkus teams.

---

## ADR-006: README as context prompt

**Status:** Accepted
**Date:** 2026-03
**Context:** AI-assisted development sessions lose context when starting new threads. Reconstructing project state from
code alone is slow and error-prone.
**Decision:** Use the README as both documentation and AI context prompt. Maintain `CONTEXT.md` for implementation
details. Update both at session end.
**Consequences:** Enables starting new AI threads with full project awareness; requires discipline to keep docs current.

---

## ADR-007: ContextRule as record inside ToolPolicy

**Status:** Accepted
**Date:** 2026-03 (Milestone 3)
**Context:** Context-aware tool policies need a data type for environment/action/tool triples.
**Decision:** Define `ContextRule` as a Java `record` nested inside `ToolPolicy`. The `environment` field is nullable (
null = match any).
**Consequences:** Keeps the type close to its policy; naturally immutable; avoids file proliferation.

---

## ADR-008: Context rules evaluated before base rules

**Status:** Accepted
**Date:** 2026-03 (Milestone 3)
**Context:** When both context-aware and base rules exist for a tool, which takes precedence?
**Decision:** Evaluation order: context rules → explicit deny → explicit consent → explicit allow → risk-based →
default. A context rule CAN override a denylist entry intentionally.
**Consequences:** More flexible; documented in test `context_rules_checked_before_base_denylist`.

---

## ADR-009: PolicyFileLoader uses reflection for Jackson

**Status:** Accepted
**Date:** 2026-03 (Milestone 3)
**Context:** Jackson is used for YAML/JSON policy files but should not be a required dependency.
**Decision:** Declare Jackson as `optional` in the POM. `PolicyFileLoader` invokes Jackson reflectively. If absent at
runtime and `fromClasspath` is called, a `PolicyLoadException` with a clear message is thrown.
**Consequences:** Runtime module compiles without Jackson; clear error message if user forgets the dependency.

---

## ADR-010: Consent handler failure always fails closed

**Status:** Accepted
**Date:** 2026-03 (Milestone 3)
**Context:** The async consent flow (`CompletableFuture<Boolean>`) can fail in multiple ways: timeout, interrupt,
exception, or explicit denial.
**Decision:** All failure modes return BLOCKED. Only `true` within timeout yields ALLOWED. Matches the global
FAIL_CLOSED design principle.
**Consequences:** No silent bypasses; safer default; documented in `ConsentFlowTests`.

---

## ADR-011: ToolPolicyEngine separate from ToolPolicy

**Status:** Accepted
**Date:** 2026-03 (Milestone 3)
**Context:** `ToolPolicy` is an immutable value object in `agent-guard-core` (zero deps). The consent flow requires a
`ConsentHandler` reference and async execution.
**Decision:** `ToolPolicy` stays pure in core. `ToolPolicyEngine` in runtime holds the `ConsentHandler` and runs the
async flow. Implements `ToolGuard` SPI.
**Consequences:** Core stays dependency-free; clear separation of data vs. behaviour.

---

## ADR-012: Resettable SPI for per-run state reset

**Status:** Accepted
**Date:** 2026-03 (Milestone 3)
**Context:** Guards with state (LoopDetector, BudgetFirewall per-run counters) need a clean reset at the start of each
agent run.
**Decision:** Guards implement `Resettable` interface. `DefaultAgentGuard.startRun()` iterates the chain calling
`reset()`.
**Consequences:** Clean, extensible, no tight coupling; new guards get reset for free by implementing the interface.
