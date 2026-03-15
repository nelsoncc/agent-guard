package io.agentguard.otel;

import io.agentguard.core.AgentGuard;
import io.agentguard.core.GuardResult;
import io.agentguard.core.ToolCall;
import io.agentguard.core.policy.BudgetPolicy;
import io.agentguard.runtime.TokenCostTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An {@link AgentGuard} decorator that adds OpenTelemetry tracing and Micrometer
 * metrics to any existing guard instance (Issues #28–#31).
 *
 * <p>This class wraps a delegate {@code AgentGuard} and intercepts every call to:
 * <ul>
 *   <li>Emit {@code gen_ai.tool.call} spans via {@link GenAiTracer} (#29)</li>
 *   <li>Record token/cost metrics via {@link TokenMeter} (#30)</li>
 *   <li>Provide a live budget gauge for Prometheus/Grafana (#31)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Build with builder (recommended)
 * AgentGuard inner = AgentGuard.builder()
 *     .budget(BudgetPolicy.perHour(BigDecimal.valueOf(2.00)))
 *     .toolPolicy(ToolPolicy.denyAll().allow("web_search").build())
 *     .build();
 *
 * AgentGuard guard = OtelAgentGuard.builder(inner)
 *     .serviceName("my-agent-service")
 *     .tracer(new GenAiTracer(openTelemetry, "my-agent-service"))
 *     .meter(TokenMeter.create(meterRegistry, "my-agent-service"))
 *     .build();
 *
 * // Use exactly like a normal AgentGuard
 * guard.startRun("run-001");
 * GuardResult result = guard.evaluateToolCall(toolCall);
 * guard.recordTokenUsage(100, 200, "gpt-4o");
 * }</pre>
 *
 * <p>Thread-safe: all delegate calls are thread-safe, and span state uses
 * {@link java.util.concurrent.ConcurrentHashMap} internally in {@link GenAiTracer}.
 */
public final class OtelAgentGuard implements AgentGuard {

    private static final Logger log = LoggerFactory.getLogger(OtelAgentGuard.class);

    private final AgentGuard delegate;
    private final GuardTracer tracer;
    private final TokenMeter meter;
    private final TokenCostTable costTable;

    private final AtomicReference<String> currentRunId = new AtomicReference<>();

    private OtelAgentGuard(AgentGuard delegate, GuardTracer tracer, TokenMeter meter) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.tracer = tracer != null ? tracer : NoOpGuardTracer.INSTANCE;
        this.meter = meter != null ? meter : TokenMeter.noOp();
        this.costTable = TokenCostTable.defaults();
    }

    // ─── AgentGuard ───────────────────────────────────────────────────────────

    @Override
    public GuardResult evaluateToolCall(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall");
        long start = System.currentTimeMillis();

        GuardResult result = delegate.evaluateToolCall(toolCall);
        long latencyMs = System.currentTimeMillis() - start;

        GuardSpan span = GuardSpan.forToolCall(
                currentRunId.get(), toolCall, result, latencyMs);

        tracer.onToolCall(span);
        meter.recordToolCall(span);

        log.debug("[OtelAgentGuard] tool='{}' status={} latency={}ms",
                toolCall.toolName(), result.status(), latencyMs);
        return result;
    }

    @Override
    public void recordTokenUsage(long inputTokens, long outputTokens, String model) {
        delegate.recordTokenUsage(inputTokens, outputTokens, model);

        // Calculate cost directly from the cost table for this invocation.
        // This avoids the per-run vs. lifetime delta bug: currentRunCost() resets
        // on startRun() but meter.costTotalUsd() is cumulative lifetime.
        double costUsd = costTable.calculateCost(inputTokens, outputTokens, model).doubleValue();

        GuardSpan span = GuardSpan.forTokenUsage(
                currentRunId.get(), inputTokens, outputTokens, model, costUsd);

        tracer.onTokenUsage(span);
        meter.recordTokenUsage(span);
    }

    @Override
    public void recordToolCallCompleted(ToolCall toolCall) {
        delegate.recordToolCallCompleted(toolCall);
    }

    @Override
    public void startRun(String runId) {
        Objects.requireNonNull(runId, "runId");
        currentRunId.set(runId);
        delegate.startRun(runId);
        tracer.onRunStart(runId);
        log.debug("[OtelAgentGuard] Run started: {}", runId);
    }

    /**
     * Ends the current run span. Call this when the agent run is complete.
     * This is additional lifecycle not on the base {@link AgentGuard} interface —
     * call it when you're done with a run to flush the root OTel span.
     */
    public void endRun() {
        String runId = currentRunId.get();
        if (runId != null) {
            tracer.onRunEnd(runId);
            log.debug("[OtelAgentGuard] Run ended: {}", runId);
        }
    }

    @Override
    public BigDecimal currentRunCost() {
        return delegate.currentRunCost();
    }

    @Override
    public BigDecimal remainingBudget() {
        return delegate.remainingBudget();
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    /**
     * The underlying guard being decorated.
     */
    public AgentGuard delegate() {
        return delegate;
    }

    /**
     * The tracer in use.
     */
    public GuardTracer tracer() {
        return tracer;
    }

    /**
     * The meter in use.
     */
    public TokenMeter meter() {
        return meter;
    }

    // ─── Builder ─────────────────────────────────────────────────────────────

    /**
     * Returns a builder for {@code OtelAgentGuard}.
     *
     * @param delegate the guard instance to decorate; must not be null
     */
    public static Builder builder(AgentGuard delegate) {
        return new Builder(delegate);
    }

    public static final class Builder {
        private final AgentGuard delegate;
        private String serviceName = "agent-guard";
        private GuardTracer tracer;
        private TokenMeter meter;

        private Builder(AgentGuard delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /**
         * Sets the service name used as a tag on all metrics and as the OTel tracer scope.
         * Default: {@code "agent-guard"}.
         */
        public Builder serviceName(String name) {
            this.serviceName = Objects.requireNonNull(name);
            return this;
        }

        /**
         * Sets a custom {@link GuardTracer}. If not set, defaults to {@link NoOpGuardTracer}.
         *
         * <p>For OTel tracing:
         * <pre>{@code
         * .tracer(GenAiTracer.create(openTelemetry, serviceName))
         * }</pre>
         */
        public Builder tracer(GuardTracer tracer) {
            this.tracer = tracer;
            return this;
        }

        /**
         * Sets a custom {@link TokenMeter}. If not set, defaults to {@link TokenMeter#noOp()}.
         *
         * <p>For Micrometer metrics:
         * <pre>{@code
         * .meter(TokenMeter.create(meterRegistry, serviceName))
         * }</pre>
         */
        public Builder meter(TokenMeter meter) {
            this.meter = meter;
            return this;
        }

        /**
         * Convenience: attaches a real OTel tracer by passing the raw
         * {@code OpenTelemetry} instance. Falls back to no-op if OTel is absent.
         *
         * @param openTelemetry {@code io.opentelemetry.api.OpenTelemetry} instance (as Object)
         */
        public Builder otelInstance(Object openTelemetry) {
            this.tracer = GenAiTracer.create(openTelemetry, serviceName);
            return this;
        }

        /**
         * Convenience: attaches a real Micrometer meter registry.
         *
         * @param meterRegistry {@code io.micrometer.core.instrument.MeterRegistry} (as Object)
         */
        public Builder meterRegistry(Object meterRegistry) {
            this.meter = TokenMeter.create(meterRegistry, serviceName,
                    () -> delegate.remainingBudget().equals(BudgetPolicy.UNLIMITED_COST)
                            ? Double.MAX_VALUE
                            : delegate.remainingBudget().doubleValue());
            return this;
        }

        public OtelAgentGuard build() {
            GuardTracer effectiveTracer = this.tracer != null ? this.tracer : NoOpGuardTracer.INSTANCE;
            TokenMeter effectiveMeter = this.meter != null ? this.meter
                    : TokenMeter.create(null, serviceName,
                    () -> delegate.remainingBudget().equals(BudgetPolicy.UNLIMITED_COST)
                            ? Double.MAX_VALUE
                            : delegate.remainingBudget().doubleValue());
            return new OtelAgentGuard(delegate, effectiveTracer, effectiveMeter);
        }
    }
}
