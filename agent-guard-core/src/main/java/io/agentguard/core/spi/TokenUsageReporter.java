package io.agentguard.core.spi;

/**
 * SPI: a component that reports token usage after a model invocation.
 *
 * <p>Implementations are LLM-provider specific:
 * an {@code OpenAiTokenCounter}, {@code AnthropicTokenCounter}, etc.
 * They extract token counts from provider API responses and convert
 * them to a cost estimate using a configurable pricing table.
 */
public interface TokenUsageReporter {

    /**
     * Reports that the given number of tokens were consumed by a model call.
     *
     * @param inputTokens  tokens in the prompt / input
     * @param outputTokens tokens in the completion / output
     * @param model        the model identifier (e.g., {@code "gpt-4o"})
     */
    void reportUsage(long inputTokens, long outputTokens, String model);
}
