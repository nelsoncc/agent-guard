package io.agentguard.core.policy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable policy that defines the token/cost limits for an agent run.
 *
 * <p>Multiple {@code BudgetPolicy} instances can be combined so that
 * different limit types (per-run, per-hour, per-day) are all enforced
 * simultaneously:
 *
 * <pre>{@code
 * BudgetPolicy runLimit  = BudgetPolicy.perRun(BigDecimal.valueOf(0.10));
 * BudgetPolicy hourLimit = BudgetPolicy.perHour(BigDecimal.valueOf(2.00));
 * BudgetPolicy dayLimit  = BudgetPolicy.perDay(BigDecimal.valueOf(10.00));
 * }</pre>
 *
 * <p>All monetary values are in USD unless the currency is overridden.
 *
 * <p>This class is <strong>immutable and thread-safe</strong>.
 */
public final class BudgetPolicy {

    /**
     * Sentinel value meaning "no limit applies".
     */
    public static final long UNLIMITED_TOKENS = Long.MAX_VALUE;
    public static final BigDecimal UNLIMITED_COST = BigDecimal.valueOf(Double.MAX_VALUE);

    private final long maxTokens;
    private final BigDecimal maxCost;
    private final Duration window;         // null = per-run (no rolling window)
    private final String currency;         // ISO 4217, default "USD"
    private final String workspaceId;      // null = applies to all workspaces
    private final String userId;           // null = applies to all users

    private BudgetPolicy(Builder builder) {
        this.maxTokens = builder.maxTokens;
        this.maxCost = builder.maxCost;
        this.window = builder.window;
        this.currency = builder.currency != null ? builder.currency : "USD";
        this.workspaceId = builder.workspaceId;
        this.userId = builder.userId;
    }

    // ─── Factory shortcuts ────────────────────────────────────────────────────

    /**
     * Budget that resets after every agent run (no rolling window).
     */
    public static BudgetPolicy perRun(BigDecimal maxCost) {
        return new Builder().maxCost(maxCost).build();
    }

    /**
     * Budget that resets after every agent run — token-based.
     */
    public static BudgetPolicy perRunTokens(long maxTokens) {
        return new Builder().maxTokens(maxTokens).build();
    }

    /**
     * Rolling 1-hour budget window.
     */
    public static BudgetPolicy perHour(BigDecimal maxCost) {
        return new Builder().maxCost(maxCost).window(Duration.ofHours(1)).build();
    }

    /**
     * Rolling 24-hour budget window.
     */
    public static BudgetPolicy perDay(BigDecimal maxCost) {
        return new Builder().maxCost(maxCost).window(Duration.ofDays(1)).build();
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    /**
     * Maximum tokens permitted. {@link #UNLIMITED_TOKENS} means no token cap.
     */
    public long maxTokens() {
        return maxTokens;
    }

    /**
     * Maximum cost permitted. {@link #UNLIMITED_COST} means no cost cap.
     */
    public BigDecimal maxCost() {
        return maxCost;
    }

    /**
     * The rolling time window over which the budget is measured.
     * If empty, the limit applies to a single run (no time window).
     */
    public Optional<Duration> window() {
        return Optional.ofNullable(window);
    }

    /**
     * Whether this policy operates on a rolling time window vs. per-run.
     */
    public boolean isRollingWindow() {
        return window != null;
    }

    /**
     * Currency code (ISO 4217, e.g. "USD", "EUR").
     */
    public String currency() {
        return currency;
    }

    /**
     * Workspace this policy applies to, or empty for all workspaces.
     */
    public Optional<String> workspaceId() {
        return Optional.ofNullable(workspaceId);
    }

    /**
     * User this policy applies to, or empty for all users.
     */
    public Optional<String> userId() {
        return Optional.ofNullable(userId);
    }

    // ─── Builder ─────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long maxTokens = UNLIMITED_TOKENS;
        private BigDecimal maxCost = UNLIMITED_COST;
        private Duration window;
        private String currency;
        private String workspaceId;
        private String userId;

        public Builder maxTokens(long maxTokens) {
            if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be positive");
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder maxCost(BigDecimal maxCost) {
            Objects.requireNonNull(maxCost, "maxCost");
            if (maxCost.compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("maxCost must be positive");
            this.maxCost = maxCost;
            return this;
        }

        public Builder window(Duration window) {
            Objects.requireNonNull(window, "window");
            if (window.isNegative() || window.isZero())
                throw new IllegalArgumentException("window must be positive");
            this.window = window;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = Objects.requireNonNull(currency, "currency");
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public BudgetPolicy build() {
            if (maxTokens == UNLIMITED_TOKENS && maxCost.equals(UNLIMITED_COST)) {
                throw new IllegalStateException(
                        "BudgetPolicy must define at least one limit (maxTokens or maxCost)");
            }
            return new BudgetPolicy(this);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BudgetPolicy{");
        if (maxTokens != UNLIMITED_TOKENS) sb.append("maxTokens=").append(maxTokens).append(", ");
        if (!maxCost.equals(UNLIMITED_COST)) sb.append("maxCost=").append(maxCost).append(currency).append(", ");
        if (window != null) sb.append("window=").append(window).append(", ");
        if (workspaceId != null) sb.append("workspace=").append(workspaceId).append(", ");
        if (sb.charAt(sb.length() - 2) == ',') sb.setLength(sb.length() - 2);
        sb.append('}');
        return sb.toString();
    }
}
