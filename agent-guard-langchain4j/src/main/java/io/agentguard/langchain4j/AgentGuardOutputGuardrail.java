package io.agentguard.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import io.agentguard.core.AgentGuard;
import io.agentguard.core.GuardResult;
import io.agentguard.core.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LangChain4j native {@link OutputGuardrail} powered by Agent Guard.
 *
 * <p>Scans the AI model's response for injection patterns, data exfiltration
 * attempts, and other threats before the response reaches the caller.
 * This catches attacks where a model is tricked into producing malicious output.
 *
 * <h2>Usage with {@code @OutputGuardrails} annotation</h2>
 * <pre>{@code
 * @OutputGuardrails(AgentGuardOutputGuardrail.class)
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
 *     .outputGuardrails(new AgentGuardOutputGuardrail(guard))
 *     .build();
 * }</pre>
 *
 * <p>This class is <strong>thread-safe</strong>.
 */
public class AgentGuardOutputGuardrail implements OutputGuardrail {

    private static final Logger log = LoggerFactory.getLogger(AgentGuardOutputGuardrail.class);
    private static final AtomicLong counter = new AtomicLong(0);

    private final AgentGuard guard;

    /**
     * Creates an output guardrail backed by the given Agent Guard instance.
     *
     * @param guard the configured guard (must have injection guard or other policies enabled)
     */
    public AgentGuardOutputGuardrail(AgentGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public OutputGuardrailResult validate(AiMessage aiMessage) {
        String text = aiMessage.text() != null ? aiMessage.text() : "";
        return doValidate(text);
    }

    @Override
    public OutputGuardrailResult validate(OutputGuardrailRequest request) {
        AiMessage aiMessage = request.responseFromLLM().aiMessage();
        String text = aiMessage.text() != null ? aiMessage.text() : "";
        return doValidate(text);
    }

    private OutputGuardrailResult doValidate(String text) {
        String callId = "output-guard-" + counter.incrementAndGet();
        ToolCall toolCall = ToolCall.builder(callId, "__ai_output__")
                .rawInput(text)
                .build();

        log.debug("[AgentGuardOutputGuardrail] Scanning AI output (length={})", text.length());

        try {
            GuardResult result = guard.evaluateToolCall(toolCall);

            if (result.wasBlocked()) {
                String reason = result.blockReason().orElse("Output blocked by Agent Guard");
                log.warn("[AgentGuardOutputGuardrail] BLOCKED: {}", reason);
                return fatal(reason);
            }

            log.debug("[AgentGuardOutputGuardrail] ALLOWED");
            return success();
        } catch (Exception e) {
            log.warn("[AgentGuardOutputGuardrail] Guard threw exception: {}", e.getMessage());
            return fatal("Agent Guard error: " + e.getMessage(), e);
        }
    }
}
