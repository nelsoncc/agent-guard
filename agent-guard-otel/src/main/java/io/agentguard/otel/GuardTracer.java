package io.agentguard.otel;

/**
 * SPI for tracing guard events with distributed tracing backends.
 *
 * <p>The default implementation is {@link NoOpGuardTracer} — it records nothing
 * and is always safe to use without any tracing infrastructure. The real
 * implementation, {@link GenAiTracer}, emits OpenTelemetry spans using the
 * {@code gen_ai.*} semantic conventions defined by the OTel GenAI working group.
 *
 * <p>This interface is intentionally free of any OTel or Micrometer types,
 * so that callers in {@code agent-guard-otel} can inject either implementation
 * without an explicit compile-time dependency on the OTel API.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // No tracing (default)
 * GuardTracer tracer = NoOpGuardTracer.INSTANCE;
 *
 * // Real OTel tracing (requires opentelemetry-api on the classpath)
 * GuardTracer tracer = new GenAiTracer(openTelemetry, "my-agent-service");
 * }</pre>
 */
public interface GuardTracer {

    /**
     * Called when an agent run starts.
     *
     * @param runId unique identifier for this run
     */
    void onRunStart(String runId);

    /**
     * Called when a tool call is evaluated by the guard chain.
     *
     * @param span data about the evaluation including result and latency
     */
    void onToolCall(GuardSpan span);

    /**
     * Called when token usage is reported after a model response.
     *
     * @param span data including token counts, model id, and estimated cost
     */
    void onTokenUsage(GuardSpan span);

    /**
     * Called when an agent run ends (normally or due to a guard block).
     *
     * @param runId the run identifier passed to {@link #onRunStart}
     */
    void onRunEnd(String runId);
}
