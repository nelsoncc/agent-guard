package io.agentguard.otel;

/**
 * A {@link GuardTracer} that does nothing.
 *
 * <p>This is the default when no tracing infrastructure is configured.
 * It is thread-safe (stateless singleton) and has zero overhead.
 */
public final class NoOpGuardTracer implements GuardTracer {

    /**
     * Shared singleton — safe to use from multiple threads.
     */
    public static final NoOpGuardTracer INSTANCE = new NoOpGuardTracer();

    private NoOpGuardTracer() {
    }

    @Override
    public void onRunStart(String runId) {
    }

    @Override
    public void onToolCall(GuardSpan span) {
    }

    @Override
    public void onTokenUsage(GuardSpan span) {
    }

    @Override
    public void onRunEnd(String runId) {
    }
}
