package io.agentguard.otel;

import io.agentguard.core.GuardResult;
import io.agentguard.core.ToolCall;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of a single guard evaluation, used as the data model
 * for OTel span attributes and Micrometer metrics.
 *
 * <p>This class is free of any OTel or Micrometer dependency — it is a plain
 * data carrier that {@link GuardTracer} and {@link TokenMeter} consume.
 */
public final class GuardSpan {

    /**
     * Type of guard event being recorded.
     */
    public enum EventType {
        /**
         * A tool call was evaluated by the guard chain.
         */
        TOOL_CALL,
        /**
         * Token/cost usage was reported after a model response.
         */
        TOKEN_USAGE,
        /**
         * An agent run started ({@link io.agentguard.core.AgentGuard#startRun}).
         */
        RUN_START,
        /**
         * An agent run completed (either normally or due to a guard block).
         */
        RUN_END
    }

    private final String runId;
    private final EventType eventType;
    private final ToolCall toolCall;           // null for non-tool events
    private final GuardResult guardResult;     // null for token-usage events
    private final long inputTokens;
    private final long outputTokens;
    private final String modelId;
    private final double costUsd;
    private final long latencyMs;
    private final Instant timestamp;

    private GuardSpan(Builder b) {
        this.runId = b.runId;
        this.eventType = Objects.requireNonNull(b.eventType, "eventType");
        this.toolCall = b.toolCall;
        this.guardResult = b.guardResult;
        this.inputTokens = b.inputTokens;
        this.outputTokens = b.outputTokens;
        this.modelId = b.modelId != null ? b.modelId : "unknown";
        this.costUsd = b.costUsd;
        this.latencyMs = b.latencyMs;
        this.timestamp = b.timestamp != null ? b.timestamp : Instant.now();
    }

    // ─── Factory shortcuts ────────────────────────────────────────────────────

    public static GuardSpan forRunStart(String runId) {
        return new Builder(EventType.RUN_START).runId(runId).build();
    }

    public static GuardSpan forRunEnd(String runId) {
        return new Builder(EventType.RUN_END).runId(runId).build();
    }

    public static GuardSpan forToolCall(String runId, ToolCall call, GuardResult result, long latencyMs) {
        return new Builder(EventType.TOOL_CALL)
                .runId(runId).toolCall(call).guardResult(result).latencyMs(latencyMs).build();
    }

    public static GuardSpan forTokenUsage(
            String runId, long inputTokens, long outputTokens, String modelId, double costUsd) {
        return new Builder(EventType.TOKEN_USAGE)
                .runId(runId)
                .inputTokens(inputTokens).outputTokens(outputTokens)
                .modelId(modelId).costUsd(costUsd).build();
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    public Optional<String> runId() {
        return Optional.ofNullable(runId);
    }

    public EventType eventType() {
        return eventType;
    }

    public Optional<ToolCall> toolCall() {
        return Optional.ofNullable(toolCall);
    }

    public Optional<GuardResult> guardResult() {
        return Optional.ofNullable(guardResult);
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    public String modelId() {
        return modelId;
    }

    public double costUsd() {
        return costUsd;
    }

    public long latencyMs() {
        return latencyMs;
    }

    public Instant timestamp() {
        return timestamp;
    }

    // ─── Builder ─────────────────────────────────────────────────────────────

    public static Builder builder(EventType type) {
        return new Builder(type);
    }

    public static final class Builder {
        private final EventType eventType;
        private String runId;
        private ToolCall toolCall;
        private GuardResult guardResult;
        private long inputTokens;
        private long outputTokens;
        private String modelId;
        private double costUsd;
        private long latencyMs;
        private Instant timestamp;

        private Builder(EventType eventType) {
            this.eventType = eventType;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder toolCall(ToolCall tc) {
            this.toolCall = tc;
            return this;
        }

        public Builder guardResult(GuardResult gr) {
            this.guardResult = gr;
            return this;
        }

        public Builder inputTokens(long n) {
            this.inputTokens = n;
            return this;
        }

        public Builder outputTokens(long n) {
            this.outputTokens = n;
            return this;
        }

        public Builder modelId(String m) {
            this.modelId = m;
            return this;
        }

        public Builder costUsd(double c) {
            this.costUsd = c;
            return this;
        }

        public Builder latencyMs(long ms) {
            this.latencyMs = ms;
            return this;
        }

        public Builder timestamp(Instant ts) {
            this.timestamp = ts;
            return this;
        }

        public GuardSpan build() {
            return new GuardSpan(this);
        }
    }
}
