package io.agentguard.core.exception;

import io.agentguard.core.GuardResult;

/**
 * Thrown when an agent attempts to call a tool that is explicitly denied
 * by the configured {@link io.agentguard.core.policy.ToolPolicy}.
 */
public class ToolDeniedException extends AgentGuardException {

    private final String toolName;

    public ToolDeniedException(String message, GuardResult guardResult, String toolName) {
        super(message, guardResult);
        this.toolName = toolName;
    }

    /**
     * The name of the tool that was denied.
     */
    public String toolName() {
        return toolName;
    }
}
