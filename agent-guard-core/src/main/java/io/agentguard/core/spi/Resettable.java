package io.agentguard.core.spi;

/**
 * SPI for {@link ToolGuard} implementations that maintain per-run state.
 *
 * <p>Guards that accumulate state across calls — such as the loop detector's
 * sliding window or the budget firewall's per-run cost counter — implement
 * this interface to integrate with {@code DefaultAgentGuard}'s run lifecycle.
 *
 * <h2>Run lifecycle</h2>
 * <ol>
 *   <li>{@link #initRun(String)} — called by {@code DefaultAgentGuard.startRun(runId)}
 *       to allocate fresh per-run state for the new run.</li>
 *   <li>Calls to {@code evaluate(ToolCall)} use the run's state keyed by {@code runId}.</li>
 *   <li>{@link #evictRun(String)} — called by {@code DefaultAgentGuard.endRun(runId)}
 *       to release memory once the run is complete.</li>
 * </ol>
 *
 * <h2>Backward compatibility</h2>
 * <p>{@link #reset()} is kept as a convenience that delegates to
 * {@code initRun("__default__")}, preserving behaviour for callers that use a single
 * shared guard without explicit run management.
 */
public interface Resettable {

    /**
     * Initialises fresh state for the given run.
     * Called by {@code DefaultAgentGuard.startRun(runId)}.
     *
     * <p>Replaces any existing state for this {@code runId}, so calling
     * {@code initRun} twice for the same run resets it to zero.
     *
     * @param runId unique identifier for the run; never null
     */
    void initRun(String runId);

    /**
     * Releases all state associated with the given run.
     * Called by {@code DefaultAgentGuard.endRun(runId)}.
     *
     * <p>After this call, any guard evaluation referencing {@code runId}
     * will fall back to a fresh (zero) state. It is safe to call
     * {@code evictRun} for an unknown run — the call is silently ignored.
     *
     * @param runId the run to evict; never null
     */
    void evictRun(String runId);

    /**
     * Resets state for the implicit default run.
     *
     * <p>Equivalent to {@code initRun("__default__")}. Preserved for
     * backward compatibility with code that does not use explicit run ids.
     */
    default void reset() {
        initRun("__default__");
    }
}
