package io.agentguard.langchain4j;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailRequest;
import dev.langchain4j.guardrail.InputGuardrailResult;
import io.agentguard.core.AgentGuard;
import io.agentguard.core.GuardResult;
import io.agentguard.core.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LangChain4j native {@link InputGuardrail} powered by Agent Guard.
 *
 * <p>Scans the user message for prompt injection, data exfiltration, and other
 * threats defined in the guard's {@code InjectionGuardPolicy}. Also enforces
 * budget and tool policies when applicable.
 *
 * <h2>Usage with {@code @InputGuardrails} annotation</h2>
 * <pre>{@code
 * // Register as a CDI/Spring bean, then reference on your AiService:
 * @InputGuardrails(AgentGuardInputGuardrail.class)
 * interface MyAgent {
 *     String chat(String message);
 * }
 * }</pre>
 *
 * <h2>Programmatic usage with AiServices builder</h2>
 * <pre>{@code
 * AgentGuard guard = AgentGuard.builder()
 *     .injectionGuard(InjectionGuardPolicy.defaultRules())
 *     .build();
 *
 * MyAgent agent = AiServices.builder(MyAgent.class)
 *     .chatModel(model)
 *     .inputGuardrails(new AgentGuardInputGuardrail(guard))
 *     .build();
 * }</pre>
 *
 * <p>This class is <strong>thread-safe</strong>.
 */
public class AgentGuardInputGuardrail implements InputGuardrail {

    private static final Logger log = LoggerFactory.getLogger(AgentGuardInputGuardrail.class);
    private static final AtomicLong counter = new AtomicLong(0);

    private final AgentGuard guard;

    /**
     * Creates an input guardrail backed by the given Agent Guard instance.
     *
     * @param guard the configured guard (must have injection guard or other policies enabled)
     */
    public AgentGuardInputGuardrail(AgentGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public InputGuardrailResult validate(InputGuardrailRequest request) {
        UserMessage userMessage = request.userMessage();
        String text = extractText(userMessage);

        String callId = "input-guard-" + counter.incrementAndGet();
        ToolCall toolCall = ToolCall.builder(callId, "__user_input__")
                .rawInput(text)
                .build();

        log.debug("[AgentGuardInputGuardrail] Scanning user input (length={})", text.length());

        try {
            GuardResult result = guard.evaluateToolCall(toolCall);

            if (result.wasBlocked()) {
                String reason = result.blockReason().orElse("Input blocked by Agent Guard");
                log.warn("[AgentGuardInputGuardrail] BLOCKED: {}", reason);
                return fatal(reason);
            }

            log.debug("[AgentGuardInputGuardrail] ALLOWED");
            return success();
        } catch (Exception e) {
            log.warn("[AgentGuardInputGuardrail] Guard threw exception: {}", e.getMessage());
            return fatal("Agent Guard error: " + e.getMessage(), e);
        }
    }

    private static String extractText(UserMessage message) {
        if (message.hasSingleText()) {
            return message.singleText();
        }
        StringBuilder sb = new StringBuilder();
        for (Content content : message.contents()) {
            if (content instanceof TextContent tc) {
                sb.append(tc.text()).append(' ');
            }
        }
        return sb.toString().trim();
    }
}
