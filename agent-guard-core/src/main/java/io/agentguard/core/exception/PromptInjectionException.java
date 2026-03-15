package io.agentguard.core.exception;

import io.agentguard.core.GuardResult;

/**
 * Thrown when the injection guard detects a prompt injection or
 * data-exfiltration pattern in agent inputs or tool outputs.
 */
public class PromptInjectionException extends AgentGuardException {

    private final String detectedPattern;
    private final String ruleDescription;

    public PromptInjectionException(
            String message,
            GuardResult guardResult,
            String detectedPattern,
            String ruleDescription) {
        super(message, guardResult);
        this.detectedPattern = detectedPattern;
        this.ruleDescription = ruleDescription;
    }

    /**
     * The regex pattern that matched.
     */
    public String detectedPattern() {
        return detectedPattern;
    }

    /**
     * Human-readable description of the injection rule that fired.
     */
    public String ruleDescription() {
        return ruleDescription;
    }
}
