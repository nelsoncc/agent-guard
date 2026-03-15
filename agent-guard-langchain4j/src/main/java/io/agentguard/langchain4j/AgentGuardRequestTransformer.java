package io.agentguard.langchain4j;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.agentguard.core.AgentGuard;
import io.agentguard.core.GuardResult;
import io.agentguard.core.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * LangChain4j {@code chatRequestTransformer} powered by Agent Guard.
 *
 * <p>Intercepts the {@link ChatRequest} before it reaches the LLM model,
 * scanning all user messages for injection patterns and policy violations.
 * If a violation is detected, a {@link RequestBlockedException} is thrown.
 *
 * <h2>Usage with AiServices builder</h2>
 * <pre>{@code
 * AgentGuard guard = AgentGuard.builder()
 *     .injectionGuard(InjectionGuardPolicy.defaultRules())
 *     .budget(BudgetPolicy.perRun(BigDecimal.valueOf(1.00)))
 *     .build();
 *
 * MyAgent agent = AiServices.builder(MyAgent.class)
 *     .chatModel(model)
 *     .chatRequestTransformer(new AgentGuardRequestTransformer(guard))
 *     .build();
 * }</pre>
 *
 * <p>This class is <strong>thread-safe</strong>.
 */
public class AgentGuardRequestTransformer implements UnaryOperator<ChatRequest> {

    private static final Logger log = LoggerFactory.getLogger(AgentGuardRequestTransformer.class);
    private static final AtomicLong counter = new AtomicLong(0);

    private final AgentGuard guard;

    /**
     * Creates a request transformer backed by the given Agent Guard instance.
     *
     * @param guard the configured guard
     */
    public AgentGuardRequestTransformer(AgentGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public ChatRequest apply(ChatRequest request) {
        for (ChatMessage message : request.messages()) {
            if (message instanceof UserMessage userMessage) {
                String text = extractText(userMessage);
                if (text.isEmpty()) continue;

                String callId = "req-transform-" + counter.incrementAndGet();
                ToolCall toolCall = ToolCall.builder(callId, "__chat_request__")
                        .rawInput(text)
                        .build();

                log.debug("[AgentGuardRequestTransformer] Scanning request message (length={})",
                        text.length());

                try {
                    GuardResult result = guard.evaluateToolCall(toolCall);

                    if (result.wasBlocked()) {
                        String reason = result.blockReason()
                                .orElse("Request blocked by Agent Guard");
                        log.warn("[AgentGuardRequestTransformer] BLOCKED: {}", reason);
                        throw new RequestBlockedException(reason, result);
                    }
                } catch (RequestBlockedException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("[AgentGuardRequestTransformer] Guard error: {}", e.getMessage());
                    throw new RequestBlockedException(
                            "Agent Guard error: " + e.getMessage(),
                            GuardResult.blocked(
                                    io.agentguard.core.ViolationType.INTERNAL_GUARD_ERROR,
                                    e.getMessage()));
                }
            }
        }

        log.debug("[AgentGuardRequestTransformer] ALLOWED — passing request through");
        return request;
    }

    private static String extractText(UserMessage message) {
        if (message.hasSingleText()) {
            return message.singleText();
        }
        StringBuilder sb = new StringBuilder();
        message.contents().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .forEach(t -> sb.append(t).append(' '));
        return sb.toString().trim();
    }

    /**
     * Thrown when the guard blocks a chat request before it reaches the LLM.
     */
    public static final class RequestBlockedException extends RuntimeException {
        private final GuardResult guardResult;

        public RequestBlockedException(String message, GuardResult result) {
            super("Agent Guard blocked request: " + message);
            this.guardResult = result;
        }

        public GuardResult guardResult() {
            return guardResult;
        }
    }
}
