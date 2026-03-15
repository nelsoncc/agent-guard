package io.agentguard.otel;

import io.agentguard.core.GuardResult;
import io.agentguard.core.GuardStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Micrometer-based metrics for Agent Guard (Issues #30, #31).
 *
 * <p>Metrics are recorded via Micrometer's {@code MeterRegistry}, making them
 * exportable to Prometheus, Grafana, Datadog, OTel Collector, and any other
 * Micrometer backend with zero code changes (#31).
 *
 * <h2>Metrics emitted</h2>
 * <table border="1">
 *   <tr><th>Metric</th><th>Type</th><th>Description</th></tr>
 *   <tr><td>{@code agent.guard.tool_calls.total}</td><td>Counter</td>
 *       <td>Total tool calls evaluated, tagged with result (allowed/blocked/consent)</td></tr>
 *   <tr><td>{@code agent.guard.blocks.total}</td><td>Counter</td>
 *       <td>Total blocked calls, tagged with violation type</td></tr>
 *   <tr><td>{@code agent.guard.tokens.input}</td><td>Counter</td>
 *       <td>Cumulative input tokens, tagged with model</td></tr>
 *   <tr><td>{@code agent.guard.tokens.output}</td><td>Counter</td>
 *       <td>Cumulative output tokens, tagged with model</td></tr>
 *   <tr><td>{@code agent.guard.cost.usd}</td><td>Counter</td>
 *       <td>Cumulative cost in USD</td></tr>
 *   <tr><td>{@code agent.guard.tool_call.latency}</td><td>Timer</td>
 *       <td>Guard evaluation latency per tool call</td></tr>
 *   <tr><td>{@code agent.guard.budget.remaining}</td><td>Gauge</td>
 *       <td>Current remaining budget in USD (per guard instance)</td></tr>
 * </table>
 *
 * <h2>Micrometer optional dependency</h2>
 * <p>Micrometer is declared {@code optional} in the POM. If {@code MeterRegistry}
 * is not on the classpath, all metrics calls become silent no-ops via the
 * {@link #noOp()} factory method.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // With a real Micrometer registry (e.g. from Spring/Quarkus DI)
 * TokenMeter meter = TokenMeter.create(meterRegistry, "production");
 *
 * // No-op when Micrometer is absent
 * TokenMeter meter = TokenMeter.noOp();
 * }</pre>
 */
public final class TokenMeter {

    private static final Logger log = LoggerFactory.getLogger(TokenMeter.class);

    // ─── Metric names ─────────────────────────────────────────────────────────

    public static final String METRIC_TOOL_CALLS = "agent.guard.tool_calls.total";
    public static final String METRIC_BLOCKS = "agent.guard.blocks.total";
    public static final String METRIC_INPUT_TOKENS = "agent.guard.tokens.input";
    public static final String METRIC_OUTPUT_TOKENS = "agent.guard.tokens.output";
    public static final String METRIC_COST_USD = "agent.guard.cost.usd";
    public static final String METRIC_LATENCY = "agent.guard.tool_call.latency";
    public static final String METRIC_BUDGET_REMAINING = "agent.guard.budget.remaining";

    // ─── Tag names ────────────────────────────────────────────────────────────

    public static final String TAG_RESULT = "result";
    public static final String TAG_VIOLATION = "violation_type";
    public static final String TAG_MODEL = "model";
    public static final String TAG_TOOL = "tool";
    public static final String TAG_SERVICE = "service";

    // ─── Instance state ───────────────────────────────────────────────────────

    // In-memory accumulators — always available regardless of Micrometer
    private final AtomicLong inputTokensTotal = new AtomicLong(0);
    private final AtomicLong outputTokensTotal = new AtomicLong(0);
    private final AtomicLong toolCallsTotal = new AtomicLong(0);
    private final AtomicLong blocksTotal = new AtomicLong(0);
    private final AtomicReference<Double> costTotal = new AtomicReference<>(0.0);

    private final Object meterRegistry;          // io.micrometer.core.instrument.MeterRegistry or null
    private final String serviceName;
    private final boolean micrometerAvailable;
    private final Supplier<Double> budgetRemainingSupplier;

    // ─── Factory methods ──────────────────────────────────────────────────────

    /**
     * Creates a {@code TokenMeter} backed by a real Micrometer {@code MeterRegistry}.
     *
     * @param meterRegistry           an {@code io.micrometer.core.instrument.MeterRegistry}
     *                                (passed as Object to avoid compile-time dependency)
     * @param serviceName             tag value for {@link #TAG_SERVICE}
     * @param budgetRemainingSupplier live gauge supplier for remaining budget; may be null
     */
    public static TokenMeter create(
            Object meterRegistry, String serviceName, Supplier<Double> budgetRemainingSupplier) {
        return new TokenMeter(meterRegistry, serviceName, budgetRemainingSupplier);
    }

    /**
     * Convenience overload without a budget gauge.
     */
    public static TokenMeter create(Object meterRegistry, String serviceName) {
        return new TokenMeter(meterRegistry, serviceName, null);
    }

    /**
     * No-op instance — all recording calls are silent.
     */
    public static TokenMeter noOp() {
        return new TokenMeter(null, "noop", null);
    }

    // ─── Constructor ─────────────────────────────────────────────────────────

    private TokenMeter(Object meterRegistry, String serviceName, Supplier<Double> budgetSupplier) {
        this.meterRegistry = meterRegistry;
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.budgetRemainingSupplier = budgetSupplier;
        this.micrometerAvailable = meterRegistry != null && isMicrometerPresent();

        if (micrometerAvailable) {
            registerGauges();
            log.debug("[TokenMeter] Micrometer metrics active for service '{}'", serviceName);
        } else if (meterRegistry != null) {
            log.warn("[TokenMeter] Micrometer not on classpath — metrics will be in-memory only");
        }
    }

    // ─── Recording methods ────────────────────────────────────────────────────

    /**
     * Records a guard evaluation for a tool call (#30).
     *
     * @param span the guard span carrying tool name, result, and latency
     */
    public void recordToolCall(GuardSpan span) {
        toolCallsTotal.incrementAndGet();

        span.guardResult().ifPresent(result -> {
            if (result.wasBlocked() || result.requiresConsent()) {
                blocksTotal.incrementAndGet();
            }
        });

        if (!micrometerAvailable) return;

        try {
            String toolName = span.toolCall().map(tc -> tc.toolName()).orElse("unknown");
            String resultTag = span.guardResult().map(r -> r.status().name().toLowerCase())
                    .orElse("allowed");

            incrementCounter(METRIC_TOOL_CALLS,
                    TAG_TOOL, toolName,
                    TAG_RESULT, resultTag,
                    TAG_SERVICE, serviceName);

            span.guardResult().ifPresent(result -> {
                if (result.wasBlocked() || result.requiresConsent()) {
                    String violation = result.violation()
                            .map(Enum::name).orElse("UNKNOWN").toLowerCase();
                    incrementCounter(METRIC_BLOCKS,
                            TAG_TOOL, toolName,
                            TAG_VIOLATION, violation,
                            TAG_SERVICE, serviceName);
                }
            });

            if (span.latencyMs() >= 0) {
                recordTimer(METRIC_LATENCY, span.latencyMs(),
                        TAG_TOOL, toolName,
                        TAG_SERVICE, serviceName);
            }
        } catch (Exception e) {
            log.debug("[TokenMeter] recordToolCall failed: {}", e.getMessage());
        }
    }

    /**
     * Records token usage after a model response (#30).
     *
     * @param span the guard span carrying token counts, model id, and cost
     */
    public void recordTokenUsage(GuardSpan span) {
        inputTokensTotal.addAndGet(span.inputTokens());
        outputTokensTotal.addAndGet(span.outputTokens());
        costTotal.updateAndGet(c -> c + span.costUsd());

        if (!micrometerAvailable) return;

        try {
            String model = span.modelId();

            incrementCounterBy(METRIC_INPUT_TOKENS, span.inputTokens(),
                    TAG_MODEL, model, TAG_SERVICE, serviceName);
            incrementCounterBy(METRIC_OUTPUT_TOKENS, span.outputTokens(),
                    TAG_MODEL, model, TAG_SERVICE, serviceName);
            incrementCounterBy(METRIC_COST_USD, span.costUsd(),
                    TAG_MODEL, model, TAG_SERVICE, serviceName);
        } catch (Exception e) {
            log.debug("[TokenMeter] recordTokenUsage failed: {}", e.getMessage());
        }
    }

    // ─── In-memory snapshot accessors (always available) ─────────────────────

    /**
     * Total input tokens recorded since creation.
     */
    public long inputTokensTotal() {
        return inputTokensTotal.get();
    }

    /**
     * Total output tokens recorded since creation.
     */
    public long outputTokensTotal() {
        return outputTokensTotal.get();
    }

    /**
     * Total tool calls evaluated.
     */
    public long toolCallsTotal() {
        return toolCallsTotal.get();
    }

    /**
     * Total calls that were blocked or required consent.
     */
    public long blocksTotal() {
        return blocksTotal.get();
    }

    /**
     * Total estimated cost in USD.
     */
    public double costTotalUsd() {
        return costTotal.get();
    }

    /**
     * Whether Micrometer is active and metrics are being exported.
     */
    public boolean isMicrometerActive() {
        return micrometerAvailable;
    }

    // ─── Reflection-based Micrometer invocation ───────────────────────────────
    //
    // Micrometer is declared optional — these methods are no-ops if the JAR
    // is absent. Using reflection keeps agent-guard-otel compilable without
    // forcing Micrometer on every adopter.

    private boolean isMicrometerPresent() {
        try {
            Class.forName("io.micrometer.core.instrument.MeterRegistry");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void registerGauges() {
        if (budgetRemainingSupplier == null) return;
        try {
            Class<?> reg = Class.forName("io.micrometer.core.instrument.MeterRegistry");
            Class<?> gaugeClass = Class.forName("io.micrometer.core.instrument.Gauge");
            // Gauge.builder(name, supplier).tag(k,v).register(registry)
            var builder = gaugeClass.getMethod("builder", String.class, Supplier.class)
                    .invoke(null, METRIC_BUDGET_REMAINING, budgetRemainingSupplier);
            var tagged = builder.getClass().getMethod("tag", String.class, String.class)
                    .invoke(builder, TAG_SERVICE, serviceName);
            tagged.getClass().getMethod("register", reg).invoke(tagged, meterRegistry);
        } catch (Exception e) {
            log.debug("[TokenMeter] registerGauges failed: {}", e.getMessage());
        }
    }

    private void incrementCounter(String name, String... tags) {
        incrementCounterBy(name, 1.0, tags);
    }

    private void incrementCounterBy(String name, double amount, String... tags) {
        try {
            Class<?> reg = Class.forName("io.micrometer.core.instrument.MeterRegistry");
            Class<?> counterClass = Class.forName("io.micrometer.core.instrument.Counter");
            // Counter.builder(name).tags(...).register(registry).increment(amount)
            var builder = counterClass.getMethod("builder", String.class).invoke(null, name);
            for (int i = 0; i < tags.length - 1; i += 2) {
                builder = builder.getClass().getMethod("tag", String.class, String.class)
                        .invoke(builder, tags[i], tags[i + 1]);
            }
            Object counter = builder.getClass().getMethod("register", reg)
                    .invoke(builder, meterRegistry);
            counter.getClass().getMethod("increment", double.class).invoke(counter, amount);
        } catch (Exception e) {
            log.debug("[TokenMeter] counter '{}' increment failed: {}", name, e.getMessage());
        }
    }

    private void recordTimer(String name, long latencyMs, String... tags) {
        try {
            Class<?> reg = Class.forName("io.micrometer.core.instrument.MeterRegistry");
            Class<?> timerClass = Class.forName("io.micrometer.core.instrument.Timer");
            var builder = timerClass.getMethod("builder", String.class).invoke(null, name);
            for (int i = 0; i < tags.length - 1; i += 2) {
                builder = builder.getClass().getMethod("tag", String.class, String.class)
                        .invoke(builder, tags[i], tags[i + 1]);
            }
            Object timer = builder.getClass().getMethod("register", reg)
                    .invoke(builder, meterRegistry);
            timer.getClass().getMethod("record", long.class,
                            Class.forName("java.util.concurrent.TimeUnit"))
                    .invoke(timer, latencyMs,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("[TokenMeter] timer '{}' record failed: {}", name, e.getMessage());
        }
    }
}
