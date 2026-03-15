package io.agentguard.otel;

import io.agentguard.core.GuardResult;
import io.agentguard.core.ToolCall;
import io.agentguard.core.ViolationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * OpenTelemetry implementation of {@link GuardTracer} using the
 * {@code gen_ai.*} semantic conventions (Issues #28, #29).
 *
 * <p>This class uses OpenTelemetry API via reflection so that the
 * {@code agent-guard-otel} module compiles without requiring the OTel JAR
 * at compile time. When {@code opentelemetry-api} <em>is</em> on the
 * runtime classpath, full tracing is active. When it is absent, a
 * {@link NoOpGuardTracer} fallback is used instead.
 *
 * <p>Prefer the convenience factory:
 * <pre>{@code
 * // Build via the OtelAgentGuard builder (recommended)
 * AgentGuard guard = OtelAgentGuard.builder(innerGuard)
 *     .serviceName("my-agent")
 *     .build();
 *
 * // Or construct directly when you already have an OpenTelemetry instance
 * GuardTracer tracer = GenAiTracer.create(openTelemetry, "my-agent-service");
 * }</pre>
 *
 * <h2>Spans emitted (#29)</h2>
 * <ul>
 *   <li>{@code gen_ai.agent.run} — root span for a complete agent run</li>
 *   <li>{@code gen_ai.tool.call} — child span for each tool evaluation</li>
 *   <li>{@code gen_ai.token.usage} — event on the root span for token reporting</li>
 * </ul>
 *
 * <h2>Attributes on all guard spans</h2>
 * <ul>
 *   <li>{@code gen_ai.tool.name}</li>
 *   <li>{@code gen_ai.tool.call.id}</li>
 *   <li>{@code gen_ai.agent.guard.block_reason}</li>
 *   <li>{@code gen_ai.agent.guard.budget_remaining}</li>
 *   <li>{@code gen_ai.agent.guard.loop_count} (future)</li>
 *   <li>{@code gen_ai.usage.input_tokens}</li>
 *   <li>{@code gen_ai.usage.output_tokens}</li>
 *   <li>{@code gen_ai.request.model}</li>
 *   <li>{@code gen_ai.agent.guard.cost_usd}</li>
 * </ul>
 */
public final class GenAiTracer implements GuardTracer {

    private static final Logger log = LoggerFactory.getLogger(GenAiTracer.class);

    // ─── OTel attribute key constants (gen_ai.* semantic conventions) ─────────

    /**
     * Span names
     */
    public static final String SPAN_AGENT_RUN = "gen_ai.agent.run";
    public static final String SPAN_TOOL_CALL = "gen_ai.tool.call";

    /**
     * Standard gen_ai attribute keys
     */
    public static final String ATTR_SYSTEM = "gen_ai.system";
    public static final String ATTR_REQUEST_MODEL = "gen_ai.request.model";
    public static final String ATTR_INPUT_TOKENS = "gen_ai.usage.input_tokens";
    public static final String ATTR_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    public static final String ATTR_TOOL_NAME = "gen_ai.tool.name";
    public static final String ATTR_TOOL_CALL_ID = "gen_ai.tool.call.id";

    /**
     * Agent Guard custom attributes (namespaced under gen_ai.agent.guard.*)
     */
    public static final String ATTR_GUARD_BLOCK_REASON = "gen_ai.agent.guard.block_reason";
    public static final String ATTR_GUARD_BUDGET_REMAINING = "gen_ai.agent.guard.budget_remaining";
    public static final String ATTR_GUARD_LOOP_COUNT = "gen_ai.agent.guard.loop_count";
    public static final String ATTR_GUARD_COST_USD = "gen_ai.agent.guard.cost_usd";
    public static final String ATTR_GUARD_VIOLATION_TYPE = "gen_ai.agent.guard.violation_type";
    public static final String ATTR_RUN_ID = "gen_ai.agent.guard.run_id";
    public static final String ATTR_LATENCY_MS = "gen_ai.agent.guard.latency_ms";

    // ─── OTel class names (for reflection-based loading) ─────────────────────

    private static final String OTEL_CLASS = "io.opentelemetry.api.OpenTelemetry";
    private static final String TRACER_CLASS = "io.opentelemetry.api.trace.Tracer";

    // ─── Instance state ───────────────────────────────────────────────────────

    private final String serviceName;
    private final Object otelTracer;       // io.opentelemetry.api.trace.Tracer or null
    private final boolean otelAvailable;

    /**
     * Active run spans keyed by runId.
     * Value type is {@code io.opentelemetry.api.trace.Span} (held as Object).
     */
    private final ConcurrentMap<String, Object> activeRunSpans = new ConcurrentHashMap<>();

    // ─── Factory methods ──────────────────────────────────────────────────────

    /**
     * Creates a {@code GenAiTracer} wrapping the given OpenTelemetry instance.
     * Falls back to a no-op if {@code openTelemetry} is null or OTel is unavailable.
     *
     * @param openTelemetry an {@code io.opentelemetry.api.OpenTelemetry} instance (passed as Object
     *                      to avoid a compile-time dependency); must not be null
     * @param serviceName   the logical name of the agent service (used as the tracer scope)
     */
    public static GuardTracer create(Object openTelemetry, String serviceName) {
        if (openTelemetry == null) {
            log.warn("[GenAiTracer] openTelemetry is null — falling back to NoOpGuardTracer");
            return NoOpGuardTracer.INSTANCE;
        }
        try {
            Class.forName(OTEL_CLASS);
            return new GenAiTracer(openTelemetry, serviceName);
        } catch (ClassNotFoundException e) {
            log.warn("[GenAiTracer] opentelemetry-api not found on classpath — "
                    + "falling back to NoOpGuardTracer. Add 'opentelemetry-api' to use OTel tracing.");
            return NoOpGuardTracer.INSTANCE;
        }
    }

    /**
     * Creates a no-op {@code GenAiTracer} without any OTel infrastructure.
     * Useful for testing the Agent Guard metrics layer without real traces.
     */
    public static GuardTracer noOp() {
        return NoOpGuardTracer.INSTANCE;
    }

    // ─── Constructor ─────────────────────────────────────────────────────────

    private GenAiTracer(Object openTelemetry, String serviceName) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.otelTracer = buildTracer(openTelemetry, serviceName);
        this.otelAvailable = this.otelTracer != null;
    }

    // ─── GuardTracer ─────────────────────────────────────────────────────────

    @Override
    public void onRunStart(String runId) {
        if (!otelAvailable || runId == null) return;
        try {
            Object span = startSpan(SPAN_AGENT_RUN);
            setAttribute(span, ATTR_RUN_ID, runId);
            activeRunSpans.put(runId, span);
            makeCurrent(span);
            log.debug("[GenAiTracer] Started run span: {}", runId);
        } catch (Exception e) {
            log.warn("[GenAiTracer] Failed to start run span: {}", e.getMessage());
        }
    }

    @Override
    public void onToolCall(GuardSpan guardSpan) {
        if (!otelAvailable) return;
        try {
            Object span = startSpan(SPAN_TOOL_CALL);

            guardSpan.toolCall().ifPresent(tc -> {
                setAttribute(span, ATTR_TOOL_NAME, tc.toolName());
                setAttribute(span, ATTR_TOOL_CALL_ID, tc.id());
            });

            setAttribute(span, ATTR_LATENCY_MS, guardSpan.latencyMs());
            guardSpan.runId().ifPresent(id -> setAttribute(span, ATTR_RUN_ID, id));

            guardSpan.guardResult().ifPresent(result -> {
                setAttribute(span, "guard.status", result.status().name());
                if (result.wasBlocked() || result.requiresConsent()) {
                    result.blockReason().ifPresent(r ->
                            setAttribute(span, ATTR_GUARD_BLOCK_REASON, r));
                    result.violation().ifPresent(v ->
                            setAttribute(span, ATTR_GUARD_VIOLATION_TYPE, v.name()));
                    setError(span, buildErrorMessage(result));
                }
            });

            endSpan(span);
            log.debug("[GenAiTracer] Recorded tool call span: {}",
                    guardSpan.toolCall().map(ToolCall::toolName).orElse("unknown"));
        } catch (Exception e) {
            log.warn("[GenAiTracer] Failed to record tool call span: {}", e.getMessage());
        }
    }

    @Override
    public void onTokenUsage(GuardSpan guardSpan) {
        if (!otelAvailable) return;
        try {
            // Emit as an event on the active run span if present
            guardSpan.runId().ifPresent(runId -> {
                Object runSpan = activeRunSpans.get(runId);
                if (runSpan != null) {
                    addEvent(runSpan, "gen_ai.token.usage", java.util.Map.of(
                            ATTR_INPUT_TOKENS, guardSpan.inputTokens(),
                            ATTR_OUTPUT_TOKENS, guardSpan.outputTokens(),
                            ATTR_REQUEST_MODEL, guardSpan.modelId(),
                            ATTR_GUARD_COST_USD, guardSpan.costUsd()
                    ));
                }
            });
            log.debug("[GenAiTracer] Recorded token usage: in={} out={} model={}",
                    guardSpan.inputTokens(), guardSpan.outputTokens(), guardSpan.modelId());
        } catch (Exception e) {
            log.warn("[GenAiTracer] Failed to record token usage event: {}", e.getMessage());
        }
    }

    @Override
    public void onRunEnd(String runId) {
        if (!otelAvailable || runId == null) return;
        try {
            Object span = activeRunSpans.remove(runId);
            if (span != null) {
                endSpan(span);
                log.debug("[GenAiTracer] Ended run span: {}", runId);
            }
        } catch (Exception e) {
            log.warn("[GenAiTracer] Failed to end run span: {}", e.getMessage());
        }
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    public String serviceName() {
        return serviceName;
    }

    public boolean isOtelActive() {
        return otelAvailable;
    }

    // ─── Reflection-based OTel API invocation ────────────────────────────────
    //
    // We use reflection so this class compiles without opentelemetry-api on
    // the classpath. All OTel types are held as Object and operated on via
    // their method names. This is identical in spirit to how many optional-dep
    // libraries (e.g. Micrometer bridge modules) handle optional integrations.

    private Object buildTracer(Object openTelemetry, String name) {
        try {
            Class<?> otelClass = Class.forName("io.opentelemetry.api.OpenTelemetry");
            var getTracerProvider = otelClass.getMethod("getTracerProvider");
            Object tracerProvider = getTracerProvider.invoke(openTelemetry);
            Class<?> tpClass = Class.forName("io.opentelemetry.api.trace.TracerProvider");
            var get = tpClass.getMethod("get", String.class);
            return get.invoke(tracerProvider, name);
        } catch (Exception e) {
            log.warn("[GenAiTracer] Could not build OTel tracer: {}", e.getMessage());
            return null;
        }
    }

    private Object startSpan(String name) throws Exception {
        Class<?> tracerClass = Class.forName("io.opentelemetry.api.trace.Tracer");
        var spanBuilderMethod = tracerClass.getMethod("spanBuilder", String.class);
        Object spanBuilder = spanBuilderMethod.invoke(otelTracer, name);
        Class<?> sbClass = Class.forName("io.opentelemetry.api.trace.SpanBuilder");
        var startSpan = sbClass.getMethod("startSpan");
        return startSpan.invoke(spanBuilder);
    }

    private void setAttribute(Object span, String key, Object value) {
        try {
            Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span");
            Class<?> attrKey = Class.forName("io.opentelemetry.api.common.AttributeKey");
            if (value instanceof String s) {
                var keyOf = attrKey.getMethod("stringKey", String.class);
                Object ak = keyOf.invoke(null, key);
                spanClass.getMethod("setAttribute", attrKey, Object.class)
                        .invoke(span, ak, s);
            } else if (value instanceof Long l) {
                var keyOf = attrKey.getMethod("longKey", String.class);
                Object ak = keyOf.invoke(null, key);
                spanClass.getMethod("setAttribute", attrKey, Object.class)
                        .invoke(span, ak, l);
            } else if (value instanceof Double d) {
                var keyOf = attrKey.getMethod("doubleKey", String.class);
                Object ak = keyOf.invoke(null, key);
                spanClass.getMethod("setAttribute", attrKey, Object.class)
                        .invoke(span, ak, d);
            } else {
                // Fallback: stringify
                setAttribute(span, key, String.valueOf(value));
            }
        } catch (Exception e) {
            log.debug("[GenAiTracer] setAttribute failed for '{}': {}", key, e.getMessage());
        }
    }

    private void setError(Object span, String message) {
        try {
            Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span");
            Class<?> statusCode = Class.forName("io.opentelemetry.api.trace.StatusCode");
            Object error = statusCode.getField("ERROR").get(null);
            spanClass.getMethod("setStatus", statusCode, String.class)
                    .invoke(span, error, message);
        } catch (Exception e) {
            log.debug("[GenAiTracer] setError failed: {}", e.getMessage());
        }
    }

    private void addEvent(Object span, String name, java.util.Map<String, Object> attrs) {
        // Simplified event recording — just call addEvent(String) for portability
        try {
            Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span");
            spanClass.getMethod("addEvent", String.class).invoke(span, name);
        } catch (Exception e) {
            log.debug("[GenAiTracer] addEvent failed: {}", e.getMessage());
        }
    }

    private void makeCurrent(Object span) {
        // Scope management — best-effort, no-throw
        try {
            Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span");
            spanClass.getMethod("makeCurrent").invoke(span);
        } catch (Exception ignored) {
        }
    }

    private void endSpan(Object span) {
        try {
            Class<?> spanClass = Class.forName("io.opentelemetry.api.trace.Span");
            spanClass.getMethod("end").invoke(span);
        } catch (Exception e) {
            log.debug("[GenAiTracer] endSpan failed: {}", e.getMessage());
        }
    }

    private static String buildErrorMessage(GuardResult result) {
        return result.blockReason()
                .map(r -> result.violation().map(v -> v.name() + ": " + r).orElse(r))
                .orElse(result.status().name());
    }
}
