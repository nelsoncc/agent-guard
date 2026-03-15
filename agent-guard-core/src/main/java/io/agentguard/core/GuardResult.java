package io.agentguard.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result of a single guard evaluation.
 *
 * <p>Every call that passes through Agent Guard produces a {@code GuardResult}.
 * It tells the caller whether to proceed ({@link GuardStatus#ALLOWED}),
 * stop ({@link GuardStatus#BLOCKED}), or pause for human approval
 * ({@link GuardStatus#REQUIRE_CONSENT}).
 *
 * <p>Factory methods:
 * <pre>{@code
 * GuardResult ok   = GuardResult.allowed();
 * GuardResult stop = GuardResult.blocked(ViolationType.BUDGET_EXCEEDED, "Monthly budget of $10 exhausted");
 * GuardResult wait = GuardResult.requireConsent("send_email", "High-risk tool requires human approval");
 * }</pre>
 */
public final class GuardResult {

    private final GuardStatus status;
    private final ViolationType violation;    // null when ALLOWED
    private final String blockReason;         // null when ALLOWED
    private final String toolName;            // null if not tool-specific
    private final long tokensConsumed;
    private final double estimatedCost;
    private final Instant timestamp;

    private GuardResult(Builder builder) {
        this.status = Objects.requireNonNull(builder.status, "status must not be null");
        this.violation = builder.violation;
        this.blockReason = builder.blockReason;
        this.toolName = builder.toolName;
        this.tokensConsumed = builder.tokensConsumed;
        this.estimatedCost = builder.estimatedCost;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
    }

    // ─── Factory shortcuts ────────────────────────────────────────────────────

    /**
     * Creates an ALLOWED result with no cost information attached.
     */
    public static GuardResult allowed() {
        return new Builder(GuardStatus.ALLOWED).build();
    }

    /**
     * Creates an ALLOWED result with token/cost context.
     */
    public static GuardResult allowed(long tokensConsumed, double estimatedCost) {
        return new Builder(GuardStatus.ALLOWED)
                .tokensConsumed(tokensConsumed)
                .estimatedCost(estimatedCost)
                .build();
    }

    /**
     * Creates a BLOCKED result.
     *
     * @param violation the type of policy violation that triggered the block
     * @param reason    human-readable description of why the action was blocked
     */
    public static GuardResult blocked(ViolationType violation, String reason) {
        return new Builder(GuardStatus.BLOCKED)
                .violation(violation)
                .blockReason(reason)
                .build();
    }

    /**
     * Creates a BLOCKED result scoped to a specific tool call.
     *
     * @param toolName  the tool that was denied
     * @param violation the policy type
     * @param reason    human-readable explanation
     */
    public static GuardResult blockedTool(String toolName, ViolationType violation, String reason) {
        return new Builder(GuardStatus.BLOCKED)
                .toolName(toolName)
                .violation(violation)
                .blockReason(reason)
                .build();
    }

    /**
     * Creates a REQUIRE_CONSENT result for a high-risk tool.
     *
     * @param toolName the tool awaiting consent
     * @param reason   explanation shown to the human approver
     */
    public static GuardResult requireConsent(String toolName, String reason) {
        return new Builder(GuardStatus.REQUIRE_CONSENT)
                .toolName(toolName)
                .violation(ViolationType.TOOL_REQUIRES_CONSENT)
                .blockReason(reason)
                .build();
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    /**
     * The overall outcome of the guard evaluation. Never null.
     */
    public GuardStatus status() {
        return status;
    }

    /**
     * @return {@code true} if execution may proceed.
     */
    public boolean isAllowed() {
        return status.isAllowed();
    }

    /**
     * @return {@code true} if execution must be stopped immediately.
     */
    public boolean wasBlocked() {
        return status.isBlocked();
    }

    /**
     * @return {@code true} if execution must be paused for human approval.
     */
    public boolean requiresConsent() {
        return status.requiresConsent();
    }

    /**
     * The category of policy violation that caused this block.
     * Present only when {@link #wasBlocked()} or {@link #requiresConsent()} is true.
     */
    public Optional<ViolationType> violation() {
        return Optional.ofNullable(violation);
    }

    /**
     * A human-readable explanation of why the action was blocked.
     * Suitable for logging and for surfaces that display guard results.
     */
    public Optional<String> blockReason() {
        return Optional.ofNullable(blockReason);
    }

    /**
     * The tool name this result is scoped to, if applicable.
     * Empty for budget/injection checks that are not tool-specific.
     */
    public Optional<String> toolName() {
        return Optional.ofNullable(toolName);
    }

    /**
     * Tokens consumed by the agent up to this evaluation point.
     */
    public long tokensConsumed() {
        return tokensConsumed;
    }

    /**
     * Estimated USD cost of the tokens consumed.
     */
    public double estimatedCost() {
        return estimatedCost;
    }

    /**
     * When this result was created.
     */
    public Instant timestamp() {
        return timestamp;
    }

    // ─── Builder ─────────────────────────────────────────────────────────────

    public static Builder builder(GuardStatus status) {
        return new Builder(status);
    }

    public static final class Builder {
        private final GuardStatus status;
        private ViolationType violation;
        private String blockReason;
        private String toolName;
        private long tokensConsumed;
        private double estimatedCost;
        private Instant timestamp;

        private Builder(GuardStatus status) {
            this.status = status;
        }

        public Builder violation(ViolationType violation) {
            this.violation = violation;
            return this;
        }

        public Builder blockReason(String reason) {
            this.blockReason = reason;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder tokensConsumed(long tokens) {
            this.tokensConsumed = tokens;
            return this;
        }

        public Builder estimatedCost(double cost) {
            this.estimatedCost = cost;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public GuardResult build() {
            return new GuardResult(this);
        }
    }

    // ─── Object ───────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("GuardResult{status=").append(status);
        if (violation != null) sb.append(", violation=").append(violation);
        if (blockReason != null) sb.append(", reason='").append(blockReason).append('\'');
        if (toolName != null) sb.append(", tool=").append(toolName);
        if (tokensConsumed > 0) sb.append(", tokens=").append(tokensConsumed);
        sb.append('}');
        return sb.toString();
    }
}
