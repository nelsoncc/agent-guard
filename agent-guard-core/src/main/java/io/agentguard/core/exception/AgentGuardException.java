package io.agentguard.core.exception;

import io.agentguard.core.GuardResult;

/**
 * Base exception for all Agent Guard runtime violations.
 *
 * <p>Callers that want to catch <em>any</em> guard violation can catch this type.
 * Callers that need to distinguish between violation types should catch the
 * specific subclasses.
 *
 * <p>Every {@code AgentGuardException} carries the {@link GuardResult} that
 * triggered it, so callers can inspect the violation type, block reason,
 * and cost information without parsing the exception message.
 */
public class AgentGuardException extends RuntimeException {

    private final GuardResult guardResult;

    public AgentGuardException(String message, GuardResult guardResult) {
        super(message);
        this.guardResult = guardResult;
    }

    public AgentGuardException(String message, GuardResult guardResult, Throwable cause) {
        super(message, cause);
        this.guardResult = guardResult;
    }

    /**
     * The guard result that caused this exception to be thrown.
     * Never null.
     */
    public GuardResult guardResult() {
        return guardResult;
    }
}
