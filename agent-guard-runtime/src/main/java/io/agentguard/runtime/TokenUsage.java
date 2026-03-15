package io.agentguard.runtime;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of tokens consumed in a single model invocation.
 *
 * <p>Used internally by {@link BudgetFirewall} to accumulate costs
 * in rolling-window buckets and per-run totals.
 */
public final class TokenUsage {

    private final long inputTokens;
    private final long outputTokens;
    private final String modelId;
    private final BigDecimal cost;          // USD, pre-calculated
    private final Instant observedAt;

    public TokenUsage(
            long inputTokens,
            long outputTokens,
            String modelId,
            BigDecimal cost,
            Instant observedAt) {
        if (inputTokens < 0) throw new IllegalArgumentException("inputTokens must be >= 0");
        if (outputTokens < 0) throw new IllegalArgumentException("outputTokens must be >= 0");
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.cost = Objects.requireNonNull(cost, "cost");
        this.observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    /**
     * Input (prompt) tokens in this observation.
     */
    public long inputTokens() {
        return inputTokens;
    }

    /**
     * Output (completion) tokens in this observation.
     */
    public long outputTokens() {
        return outputTokens;
    }

    /**
     * Total tokens (input + output).
     */
    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    /**
     * Model that produced this usage.
     */
    public String modelId() {
        return modelId;
    }

    /**
     * Pre-calculated USD cost for this observation.
     */
    public BigDecimal cost() {
        return cost;
    }

    /**
     * When this observation was recorded.
     */
    public Instant observedAt() {
        return observedAt;
    }

    @Override
    public String toString() {
        return "TokenUsage{in=" + inputTokens + ", out=" + outputTokens
                + ", model=" + modelId + ", cost=$" + cost + "}";
    }
}
