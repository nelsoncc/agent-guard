package io.agentguard.core.exception;

import io.agentguard.core.GuardResult;

/**
 * Thrown when the loop detector determines the agent is stuck in a
 * repetitive call pattern.
 */
public class LoopDetectedException extends AgentGuardException {

    private final String repeatedToolName;
    private final int repeatCount;
    private final int windowSize;

    public LoopDetectedException(
            String message,
            GuardResult guardResult,
            String repeatedToolName,
            int repeatCount,
            int windowSize) {
        super(message, guardResult);
        this.repeatedToolName = repeatedToolName;
        this.repeatCount = repeatCount;
        this.windowSize = windowSize;
    }

    /**
     * The tool that was called repeatedly.
     */
    public String repeatedToolName() {
        return repeatedToolName;
    }

    /**
     * How many times the tool was called with identical arguments.
     */
    public int repeatCount() {
        return repeatCount;
    }

    /**
     * The window size within which the repeats were detected.
     */
    public int windowSize() {
        return windowSize;
    }
}
