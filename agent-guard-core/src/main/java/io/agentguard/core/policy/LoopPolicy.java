package io.agentguard.core.policy;

import java.util.Objects;

/**
 * Immutable policy that configures the loop detection algorithm.
 *
 * <p>The loop detector maintains a sliding window of recent tool calls and
 * fires when the same tool is called with identical (or semantically similar)
 * arguments more than {@link #maxRepeats()} times within the last
 * {@link #windowSize()} calls.
 *
 * <pre>{@code
 * LoopPolicy policy = LoopPolicy.defaults();
 *
 * // Custom: allow 2 repeats in a window of 5 calls, with backoff before interrupting
 * LoopPolicy policy = LoopPolicy.builder()
 *     .maxRepeats(2)
 *     .windowSize(5)
 *     .backoffBeforeInterrupt(true)
 *     .build();
 * }</pre>
 *
 * <p>This class is <strong>immutable and thread-safe</strong>.
 */
public final class LoopPolicy {

    /**
     * Default number of identical calls before the loop is flagged.
     */
    public static final int DEFAULT_MAX_REPEATS = 3;

    /**
     * Default size of the sliding call window.
     */
    public static final int DEFAULT_WINDOW_SIZE = 10;

    private final int maxRepeats;
    private final int windowSize;
    private final boolean backoffBeforeInterrupt;
    private final boolean semanticDetectionEnabled;

    private LoopPolicy(Builder builder) {
        this.maxRepeats = builder.maxRepeats;
        this.windowSize = builder.windowSize;
        this.backoffBeforeInterrupt = builder.backoffBeforeInterrupt;
        this.semanticDetectionEnabled = builder.semanticDetectionEnabled;
    }

    // ─── Factory shortcuts ────────────────────────────────────────────────────

    /**
     * Sensible defaults: 3 repeats in a 10-call window, backoff enabled.
     */
    public static LoopPolicy defaults() {
        return new Builder().build();
    }

    /**
     * Disable all loop detection (not recommended for production).
     */
    public static LoopPolicy disabled() {
        return new Builder().maxRepeats(Integer.MAX_VALUE).build();
    }

    /**
     * Convenience method matching the API shown in the README.
     */
    public static Builder maxRepeats(int maxRepeats) {
        return new Builder().maxRepeats(maxRepeats);
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    /**
     * Number of identical tool calls within the window that triggers a block.
     */
    public int maxRepeats() {
        return maxRepeats;
    }

    /**
     * Number of most-recent tool calls tracked in the sliding window.
     */
    public int windowSize() {
        return windowSize;
    }

    /**
     * If {@code true}, the guard will issue one warning and apply an
     * increasing delay before finally blocking.
     * Pattern: warn once, delay 2×, then BLOCK.
     */
    public boolean backoffBeforeInterrupt() {
        return backoffBeforeInterrupt;
    }

    /**
     * If {@code true}, the loop detector also checks for semantically
     * equivalent calls (same tool, slightly different arguments that produce
     * the same outcome). Requires Milestone 2 implementation.
     */
    public boolean semanticDetectionEnabled() {
        return semanticDetectionEnabled;
    }

    // ─── Builder ─────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxRepeats = DEFAULT_MAX_REPEATS;
        private int windowSize = DEFAULT_WINDOW_SIZE;
        private boolean backoffBeforeInterrupt = true;
        private boolean semanticDetectionEnabled = false;

        public Builder maxRepeats(int maxRepeats) {
            if (maxRepeats < 1)
                throw new IllegalArgumentException("maxRepeats must be >= 1");
            this.maxRepeats = maxRepeats;
            return this;
        }

        public Builder withinLastNCalls(int windowSize) {
            if (windowSize < 1)
                throw new IllegalArgumentException("windowSize must be >= 1");
            this.windowSize = windowSize;
            return this;
        }

        public Builder backoffBeforeInterrupt(boolean enabled) {
            this.backoffBeforeInterrupt = enabled;
            return this;
        }

        public Builder semanticDetection(boolean enabled) {
            this.semanticDetectionEnabled = enabled;
            return this;
        }

        public LoopPolicy build() {
            if (maxRepeats != Integer.MAX_VALUE && maxRepeats > windowSize) {
                throw new IllegalStateException(
                        "maxRepeats (" + maxRepeats + ") cannot exceed windowSize (" + windowSize + ")");
            }
            return new LoopPolicy(this);
        }
    }

    @Override
    public String toString() {
        return "LoopPolicy{maxRepeats=" + maxRepeats
                + ", windowSize=" + windowSize
                + ", backoff=" + backoffBeforeInterrupt
                + ", semantic=" + semanticDetectionEnabled + '}';
    }
}
