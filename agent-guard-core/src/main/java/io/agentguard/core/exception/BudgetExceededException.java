package io.agentguard.core.exception;

import io.agentguard.core.GuardResult;

import java.math.BigDecimal;

/**
 * Thrown when an agent's token or cost budget has been exhausted.
 *
 * <pre>{@code
 * try {
 *     guardedAgent.run("do task X");
 * } catch (BudgetExceededException e) {
 *     log.warn("Agent overspent: consumed={} limit={}",
 *              e.consumed(), e.limit());
 * }
 * }</pre>
 */
public class BudgetExceededException extends AgentGuardException {

    private final BigDecimal consumed;
    private final BigDecimal limit;
    private final long tokensConsumed;
    private final long tokenLimit;

    public BudgetExceededException(
            String message,
            GuardResult guardResult,
            BigDecimal consumed,
            BigDecimal limit,
            long tokensConsumed,
            long tokenLimit) {
        super(message, guardResult);
        this.consumed = consumed;
        this.limit = limit;
        this.tokensConsumed = tokensConsumed;
        this.tokenLimit = tokenLimit;
    }

    /**
     * Actual cost accumulated so far (USD).
     */
    public BigDecimal consumed() {
        return consumed;
    }

    /**
     * The cost limit that was exceeded (USD).
     */
    public BigDecimal limit() {
        return limit;
    }

    /**
     * Total tokens consumed so far.
     */
    public long tokensConsumed() {
        return tokensConsumed;
    }

    /**
     * Token limit that was exceeded ({@code Long.MAX_VALUE} if not set).
     */
    public long tokenLimit() {
        return tokenLimit;
    }
}
