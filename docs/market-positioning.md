# Agent Guard - public GitHub positioning notes

Use this note as a companion to the public README.

## Positioning to keep

- runtime governance
- tool authorization
- budget firewall
- approval-gated actions
- loop braking
- Java-native SDK
- governance layer on top of LangChain4j / Spring / Quarkus

## Positioning to avoid

- “the AI guardrails platform”
- “the only solution on the market”
- “complete observability platform”
- “unique prompt injection protection”
- “full semantic runtime intelligence”

## Best one-line pitch

**Agent Guard is a Java-native runtime governance SDK for tool-using AI agents.**

## Best short comparison

Frameworks help you build agents.
Observability tools help you inspect them.
Cloud safety products help you filter content.

**Agent Guard helps you control which tool actions are allowed to happen inside your runtime.**

## Recommended audience

- Java platform teams
- backend teams adding agentic behavior to existing apps
- teams using LangChain4j, Quarkus, or Spring AI
- teams that need approvals, spend controls, or tool authorization

## Strongest differentiators

- inline budget enforcement
- reusable tool policy semantics
- human approval for risky actions
- loop brakes inside the app runtime
- portable governance layer across Java stacks
- **only Java-native option** - all comparable governance SDKs in 2026 are Python or TypeScript

---

## Competitive landscape (March 2026)

The runtime governance space grew significantly in early 2026. These are the closest comparable projects:

| Project                            | Focus                                                                | Language       | Notes                                                      |
|------------------------------------|----------------------------------------------------------------------|----------------|------------------------------------------------------------|
| Microsoft Agent Governance Toolkit | Policy enforcement, zero-trust identity, sandboxing, budget tracking | Python         | MIT license, 12+ framework integrations, OWASP 10/10       |
| Galileo Agent Control              | Centralized control plane, pluggable evaluators                      | Python         | Apache 2.0, launched March 11 2026                         |
| Sekuire                            | Runtime governance SDK, policy-as-code, OAGS spec                    | TypeScript     | Offline-first, enterprise dashboard                        |
| APort Agent Guardrails             | Pre-action authorization, kill switch, W3C DID passports             | Node.js/Python | Ed25519 signing, fail-closed                               |
| Traccia                            | Agent tracing, loop detection, budget control                        | Python         | Universal SDK for LangChain/CrewAI/AutoGen                 |
| CyberArk Agent Guard               | Secret management, MCP proxy auditing                                | Python         | Different focus: identity and secrets, not tool governance |

**Key observation:** none of these are Java-native. This is Agent Guard's clearest moat.

### What frameworks already provide (validated March 2026)

| Capability                   | LangChain4j                         | Quarkus LangChain4j                | Spring AI                |
|------------------------------|-------------------------------------|------------------------------------|--------------------------|
| Input/output guardrail hooks | Yes (interfaces, no built-in impls) | Yes (tool input/output guardrails) | Yes (Advisors API)       |
| Budget/cost enforcement      | No                                  | No (example only, not native)      | No                       |
| Human approval/consent       | No                                  | No                                 | No                       |
| Loop detection               | No                                  | No                                 | No                       |
| Rate limiting                | No                                  | Custom impl possible               | No                       |
| Tool authorization           | No                                  | Via SecurityIdentity injection     | Via ToolCallbackResolver |
| OTel/metrics                 | Experimental                        | Built-in                           | Built-in                 |
| MCP security                 | No                                  | MCP client support                 | OAuth2/API key for MCP   |

### What Microsoft Agent Framework provides

Microsoft Agent Framework (RC1, Feb 2026) does have native human approval via `ApprovalRequiredAIFunction`. This is
relevant context - human consent is not unique to Agent Guard market-wide, but it remains absent from the Java
frameworks (LangChain4j, Spring AI, Quarkus).

---

## References

### Java frameworks

- LangChain4j guardrails and core docs: <https://docs.langchain4j.dev/>
- LangChain4j guardrails tutorial: <https://docs.langchain4j.dev/tutorials/guardrails/>
- LangChain4j common guardrails discussion: <https://github.com/langchain4j/langchain4j/issues/3248>
- Quarkus LangChain4j overview: <https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html>
- Quarkus tool guardrails: <https://docs.quarkiverse.io/quarkus-langchain4j/dev/tool-guardrails.html>
- Quarkus MCP support: <https://docs.quarkiverse.io/quarkus-langchain4j/dev/mcp.html>
- Spring AI Advisors: <https://docs.spring.io/spring-ai/reference/api/advisors.html>
- Spring AI tool calling: <https://docs.spring.io/spring-ai/reference/api/tools.html>
- Spring AI MCP security: <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html>
- Spring I/O 2026 - Spring AI
  Ecosystem: <https://2026.springio.net/sessions/the-spring-ai-ecosystem-in-2026-from-foundations-to-agents/>

### Governance SDKs and platforms (2026)

- Microsoft Agent Governance Toolkit: <https://github.com/microsoft/agent-governance-toolkit>
- Galileo Agent Control: <https://galileo.ai/blog/announcing-agent-control>
- Sekuire: <https://sekuire.ai/>
- APort Agent Guardrails: <https://github.com/aporthq/aport-agent-guardrails>
- Traccia: <https://traccia.ai/>
- CyberArk Agent Guard: <https://github.com/cyberark/agent-guard>
- Microsoft Agent Framework tool
  approval: <https://learn.microsoft.com/en-us/agent-framework/tutorials/agents/function-tools-approvals>

### Cloud safety and observability

- OpenTelemetry GenAI conventions: <https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-events/>
- Amazon Bedrock Guardrails: <https://docs.aws.amazon.com/bedrock/latest/userguide/guardrails.html>
- Azure Prompt
  Shields: <https://learn.microsoft.com/en-us/azure/ai-services/content-safety/concepts/jailbreak-detection>
- Vertex AI safety
  filtering: <https://docs.cloud.google.com/vertex-ai/generative-ai/docs/multimodal/gemini-for-filtering-and-moderation>

### Market analysis

- AccuKnox - Top Runtime AI Governance Platforms
  2026: <https://accuknox.com/blog/runtime-ai-governance-security-platforms-llm-systems-2026>
- Enterprise AI Agent Governance - Layered
  Approach: <https://securityboulevard.com/2026/03/enterprise-ai-agent-governance-a-layered-approach-build-deployment-and-runtime/>