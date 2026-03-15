package io.agentguard.runtime;

import io.agentguard.core.GuardResult;
import io.agentguard.core.ViolationType;
import io.agentguard.core.ToolCall;
import io.agentguard.core.policy.LoopPolicy;
import io.agentguard.core.spi.Resettable;
import io.agentguard.core.spi.ToolGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guard that detects when an agent is stuck repeating the same tool call.
 *
 * <h2>Algorithm</h2>
 * <p>Maintains a per-run bounded sliding window (size = {@link LoopPolicy#windowSize()})
 * of the most recent tool calls. Each run has completely isolated state, so concurrent
 * agent runs sharing a single {@code LoopDetector} instance never interfere with each other.
 *
 * <h3>Exact repetition</h3>
 * <p>Two calls are exact-equal when their tool name and argument map produce the
 * same {@link CallSignature#exactKey()}.
 *
 * <h3>Semantic repetition</h3>
 * <p>When {@link LoopPolicy#semanticDetectionEnabled()} is {@code true}, the detector
 * also compares {@link CallSignature#semanticKey()} values, normalised to lowercase
 * and collapsed whitespace.
 *
 * <h3>Backoff before interrupt</h3>
 * <p>When {@link LoopPolicy#backoffBeforeInterrupt()} is {@code true}, a one-time
 * warning is logged at {@code maxRepeats - 1}, giving the agent one last chance before
 * the hard block at {@code maxRepeats}.
 *
 * <h2>Run isolation (#1 fix)</h2>
 * <p>Per-run state is keyed by a {@code runId} extracted from
 * {@link ToolCall#runId()}. A guard instance can safely be shared across any number
 * of concurrent runs. Call {@link #initRun(String)} at the start of each run and
 * {@link #evictRun(String)} at the end to manage memory in long-lived services.
 *
 * <p>This class is <strong>thread-safe</strong>.
 */
public final class LoopDetector implements ToolGuard, Resettable {

    private static final Logger log = LoggerFactory.getLogger(LoopDetector.class);

    /**
     * Sentinel key used when no runId is present on the ToolCall.
     */
    static final String DEFAULT_RUN = "__default__";

    private final LoopPolicy policy;

    /**
     * Per-run mutable state.  Key = runId (or {@link #DEFAULT_RUN}).
     * Values are accessed only via synchronized blocks on the RunState instance itself.
     */
    private final ConcurrentHashMap<String, RunState> runStates = new ConcurrentHashMap<>();

    public LoopDetector(LoopPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        // Pre-create the default run state for single-run usage without explicit runId
        runStates.put(DEFAULT_RUN, new RunState(policy.windowSize()));
    }

    // ─── ToolGuard ────────────────────────────────────────────────────────────

    @Override
    public GuardResult evaluate(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall");

        if (policy.maxRepeats() == Integer.MAX_VALUE) {
            return GuardResult.allowed();
        }

        String runId = toolCall.runId().orElse(DEFAULT_RUN);
        RunState state = runStates.computeIfAbsent(runId,
                id -> new RunState(policy.windowSize()));

        CallSignature sig = CallSignature.of(toolCall);

        synchronized (state) {
            // 1. Add to window and trim to windowSize
            state.window.addLast(sig);
            while (state.window.size() > policy.windowSize()) {
                state.window.pollFirst();
            }

            // 2. Count occurrences
            int exactCount = countByExactKey(state, sig.exactKey());
            int semanticCount = policy.semanticDetectionEnabled()
                    ? countBySemanticKey(state, sig.semanticKey())
                    : 0;

            boolean exactWorse = exactCount >= semanticCount;
            int worstCount = exactWorse ? exactCount : semanticCount;
            String matchType = exactWorse ? "exact" : "semantic";
            String matchedKey = exactWorse ? sig.exactKey() : sig.semanticKey();

            // 3. Hard block at maxRepeats
            if (worstCount >= policy.maxRepeats()) {
                String reason = String.format(
                        "Loop detected: '%s' called %d times (%s match) within last %d calls",
                        toolCall.toolName(), worstCount, matchType, policy.windowSize());
                log.warn("[LoopDetector] BLOCKED (run='{}') — {}", runId, reason);
                return GuardResult.blockedTool(toolCall.toolName(), ViolationType.LOOP_DETECTED, reason);
            }

            // 4. Backoff warning at maxRepeats-1
            if (policy.backoffBeforeInterrupt() && worstCount == policy.maxRepeats() - 1) {
                if (state.warnedKeys.add(matchedKey)) {
                    log.warn("[LoopDetector] WARNING (run='{}') — '{}' called {} time(s) ({} match). " +
                                    "One more repeat will be blocked.",
                            runId, toolCall.toolName(), worstCount, matchType);
                }
            }

            return GuardResult.allowed();
        }
    }

    // ─── Resettable ───────────────────────────────────────────────────────────

    /**
     * Initialises (or re-initialises) state for the given run.
     * If the run already has state, it is cleared — equivalent to a reset.
     */
    @Override
    public void initRun(String runId) {
        Objects.requireNonNull(runId, "runId");
        runStates.put(runId, new RunState(policy.windowSize()));
        log.debug("[LoopDetector] State initialised for run '{}'.", runId);
    }

    /**
     * Releases state for the given run, freeing memory.
     * Safe to call for unknown run ids.
     */
    @Override
    public void evictRun(String runId) {
        Objects.requireNonNull(runId, "runId");
        if (runStates.remove(runId) != null) {
            log.debug("[LoopDetector] State evicted for run '{}'.", runId);
        }
    }

    // ─── Accessors for testing ────────────────────────────────────────────────

    /**
     * Window snapshot for the given runId. Returns empty list if run not found.
     */
    synchronized java.util.List<CallSignature> windowSnapshot(String runId) {
        RunState state = runStates.get(runId);
        if (state == null) return java.util.List.of();
        synchronized (state) {
            return Collections.unmodifiableList(new java.util.ArrayList<>(state.window));
        }
    }

    /**
     * Window snapshot for the default run (backward-compat accessor for tests).
     */
    synchronized java.util.List<CallSignature> windowSnapshot() {
        return windowSnapshot(DEFAULT_RUN);
    }

    int windowSize() {
        RunState state = runStates.get(DEFAULT_RUN);
        if (state == null) return 0;
        synchronized (state) {
            return state.window.size();
        }
    }

    // ─── Inner state class ────────────────────────────────────────────────────

    /**
     * Mutable, per-run state. All access must be guarded by synchronized(this).
     */
    private static final class RunState {
        final ArrayDeque<CallSignature> window;
        final Set<String> warnedKeys = new HashSet<>();

        RunState(int windowCapacity) {
            this.window = new ArrayDeque<>(windowCapacity);
        }
    }

    // ─── Window counting helpers (called while holding RunState monitor) ──────

    private static int countByExactKey(RunState state, String key) {
        int count = 0;
        for (CallSignature s : state.window) {
            if (s.exactKey().equals(key)) count++;
        }
        return count;
    }

    private static int countBySemanticKey(RunState state, String key) {
        int count = 0;
        for (CallSignature s : state.window) {
            if (s.semanticKey().equals(key)) count++;
        }
        return count;
    }
}
