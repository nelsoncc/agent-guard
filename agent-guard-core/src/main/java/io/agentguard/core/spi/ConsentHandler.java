package io.agentguard.core.spi;

import io.agentguard.core.ToolCall;

import java.util.concurrent.CompletableFuture;

/**
 * SPI: handles the human-approval flow for tools marked with
 * {@link io.agentguard.core.GuardStatus#REQUIRE_CONSENT}.
 *
 * <p>Implementations can use any mechanism to request consent:
 * a Slack message, a UI dialog, an email, or a simple CLI prompt.
 * The {@link #requestConsent(ToolCall, String)} method must return a
 * {@link CompletableFuture} that resolves to {@code true} (approved)
 * or {@code false} (denied) when the human responds.
 *
 * <p>The default implementation ({@code BlockingConsentHandler}) blocks
 * the calling thread and waits for a system-property or environment-variable
 * override — useful for integration tests.
 */
public interface ConsentHandler {

    /**
     * Asynchronously requests human approval for a tool call.
     *
     * @param toolCall the tool invocation awaiting approval
     * @param reason   the reason provided by the guard (shown to the approver)
     * @return a future resolving to {@code true} if approved, {@code false} if denied
     */
    CompletableFuture<Boolean> requestConsent(ToolCall toolCall, String reason);
}
