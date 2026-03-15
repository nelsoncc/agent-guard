package io.agentguard.langchain4j;

import io.agentguard.core.AgentGuard;
import io.agentguard.core.GuardResult;
import io.agentguard.core.ToolCall;
import io.agentguard.core.ViolationType;
import io.agentguard.core.exception.AgentGuardException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LangChain4j integration: wraps any AI service interface with Agent Guard
 * protection (Issue #34).
 *
 * <p>LangChain4j creates AI service instances via {@code AiServices.create(MyService.class, model)}.
 * {@code GuardedAiService} wraps the resulting proxy with another proxy that intercepts
 * every method call, translates it to a {@link ToolCall}, and evaluates it through the guard
 * before allowing execution.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Build your LangChain4j AI service normally
 * MyAiService raw = AiServices.create(MyAiService.class, chatModel);
 *
 * // Wrap it with Agent Guard
 * AgentGuard guard = AgentGuard.builder()
 *     .toolPolicy(ToolPolicy.denyAll().allow("search").allow("summarise").build())
 *     .budget(BudgetPolicy.perRun(BigDecimal.valueOf(0.50)))
 *     .build();
 *
 * MyAiService guarded = GuardedAiService.wrap(raw, MyAiService.class, guard);
 *
 * // Use exactly like the original — guard is transparent when calls are allowed
 * String result = guarded.search("agent governance java");
 * }</pre>
 *
 * <h2>How method names map to tool names</h2>
 * <p>By default the Java method name is used as the tool name (e.g. method
 * {@code searchWeb} → tool {@code searchWeb}). You can override the mapping
 * using the builder:
 * <pre>{@code
 * MyAiService guarded = GuardedAiService.builder(raw, MyAiService.class, guard)
 *     .withToolName("searchWeb", "web_search")  // rename for policy matching
 *     .build();
 * }</pre>
 *
 * <p>Calls to {@link Object} methods ({@code toString}, {@code hashCode},
 * {@code equals}) bypass the guard entirely.
 *
 * <p>This class is <strong>thread-safe</strong>.
 */
public final class GuardedAiService<T> {

    private static final Logger log = LoggerFactory.getLogger(GuardedAiService.class);

    // ─── Static factory ───────────────────────────────────────────────────────

    /**
     * Wraps an existing AI service instance with Agent Guard protection.
     *
     * @param delegate         the LangChain4j-created AI service proxy
     * @param serviceInterface the interface type (needed to create the wrapping proxy)
     * @param guard            the configured guard
     * @param <T>              the AI service interface type
     * @return a new proxy implementing {@code T} that routes all calls through the guard
     */
    public static <T> T wrap(T delegate, Class<T> serviceInterface, AgentGuard guard) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(serviceInterface, "serviceInterface");
        Objects.requireNonNull(guard, "guard");
        GuardedAiService<T> wrapper = new GuardedAiService<>(delegate, guard);
        return wrapper.createProxy(serviceInterface);
    }

    /**
     * Returns a builder that lets you configure tool-name overrides before creating
     * the guarded proxy.
     *
     * <pre>{@code
     * MyService guarded = GuardedAiService.builder(raw, MyService.class, guard)
     *     .withToolName("searchWeb", "web_search")
     *     .build();
     * }</pre>
     */
    public static <T> GuardedAiServiceBuilder<T> builder(
            T delegate, Class<T> serviceInterface, AgentGuard guard) {
        return new GuardedAiServiceBuilder<>(delegate, serviceInterface, guard);
    }

    /**
     * Fluent builder for {@link GuardedAiService} with tool-name override support.
     */
    public static final class GuardedAiServiceBuilder<T> {
        private final T delegate;
        private final Class<T> serviceInterface;
        private final AgentGuard guard;
        private final Map<String, String> overrides = new LinkedHashMap<>();

        private GuardedAiServiceBuilder(T delegate, Class<T> serviceInterface, AgentGuard guard) {
            this.delegate = Objects.requireNonNull(delegate);
            this.serviceInterface = Objects.requireNonNull(serviceInterface);
            this.guard = Objects.requireNonNull(guard);
        }

        /**
         * Maps a Java method name (or {@code @Tool} name) to the tool name used for
         * policy lookup. Useful when your policy uses a different naming convention
         * than your Java method names.
         *
         * @param methodOrToolName the method name as declared in Java, or the {@code @Tool} name
         * @param policyToolName   the name to use when evaluating the guard policy
         */
        public GuardedAiServiceBuilder<T> withToolName(String methodOrToolName, String policyToolName) {
            overrides.put(
                    Objects.requireNonNull(methodOrToolName, "methodOrToolName"),
                    Objects.requireNonNull(policyToolName, "policyToolName"));
            return this;
        }

        /**
         * Creates the guarded proxy.
         */
        public T build() {
            GuardedAiService<T> wrapper = new GuardedAiService<>(delegate, guard);
            wrapper.toolNameOverrides.putAll(overrides);
            return wrapper.createProxy(serviceInterface);
        }
    }

    // ─── Instance state ───────────────────────────────────────────────────────

    private final T delegate;
    private final AgentGuard guard;
    private final Map<String, String> toolNameOverrides = new LinkedHashMap<>();
    private final AtomicInteger callCounter = new AtomicInteger(0);

    private GuardedAiService(T delegate, AgentGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    // ─── Proxy creation ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private T createProxy(Class<T> iface) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                new GuardInvocationHandler());
    }

    // ─── InvocationHandler ────────────────────────────────────────────────────

    private final class GuardInvocationHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Always pass through Object methods (equals, hashCode, toString)
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }

            String toolName = resolveToolName(method);
            String callId = "lc4j-" + callCounter.incrementAndGet();
            Map<String, Object> arguments = buildArguments(method, args);

            ToolCall toolCall = ToolCall.builder(callId, toolName)
                    .arguments(arguments)
                    .rawInput(rawInput(method, args))
                    .build();

            log.debug("[GuardedAiService] Evaluating tool='{}' callId='{}'", toolName, callId);

            GuardResult result = guard.evaluateToolCall(toolCall);

            if (result.wasBlocked()) {
                String reason = result.blockReason().orElse("Guard blocked this call");
                log.warn("[GuardedAiService] BLOCKED tool='{}': {}", toolName, reason);
                throw new GuardedCallBlockedException(toolName, result, reason);
            }

            if (result.requiresConsent()) {
                // Consent was required but the guard returned REQUIRE_CONSENT without resolving it.
                // This shouldn't normally reach here (ToolPolicyEngine resolves consent),
                // but handle defensively.
                String reason = result.blockReason().orElse("Consent required");
                log.warn("[GuardedAiService] CONSENT REQUIRED tool='{}': {}", toolName, reason);
                throw new GuardedCallBlockedException(toolName, result, reason);
            }

            // Guard approved — execute the real method
            try {
                log.debug("[GuardedAiService] ALLOWED tool='{}' — invoking delegate", toolName);
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause(); // unwrap
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String resolveToolName(Method method) {
        // Check LangChain4j @Tool annotation name via reflection (no compile dep)
        try {
            Class<?> toolAnnotation = Class.forName("dev.langchain4j.agent.tool.Tool");
            var ann = method.getAnnotation((Class) toolAnnotation);
            if (ann != null) {
                String name = (String) ann.getClass().getMethod("name").invoke(ann);
                if (name != null && !name.isBlank()) {
                    return toolNameOverrides.getOrDefault(name, name);
                }
            }
        } catch (Exception ignored) {
            // LangChain4j not on classpath or annotation absent — use method name
        }
        return toolNameOverrides.getOrDefault(method.getName(), method.getName());
    }

    private static Map<String, Object> buildArguments(Method method, Object[] args) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (args == null) return map;
        var params = method.getParameters();
        for (int i = 0; i < args.length; i++) {
            String paramName = params[i].isNamePresent() ? params[i].getName() : "arg" + i;
            map.put(paramName, args[i]);
        }
        return map;
    }

    private static String rawInput(Method method, Object[] args) {
        if (args == null || args.length == 0) return method.getName() + "()";
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i]);
        }
        return sb.append(')').toString();
    }

    // ─── Exception ────────────────────────────────────────────────────────────

    /**
     * Thrown when the guard blocks a LangChain4j AI service call.
     * Callers can inspect {@link #guardResult()} for full details.
     */
    public static final class GuardedCallBlockedException extends RuntimeException {
        private final GuardResult guardResult;
        private final String toolName;

        public GuardedCallBlockedException(String toolName, GuardResult result, String message) {
            super("Guard blocked call to '" + toolName + "': " + message);
            this.toolName = toolName;
            this.guardResult = result;
        }

        public GuardResult guardResult() {
            return guardResult;
        }

        public String toolName() {
            return toolName;
        }

        public boolean wasBudgetExceeded() {
            return guardResult.violation().map(v -> v == ViolationType.BUDGET_EXCEEDED).orElse(false);
        }

        public boolean wasInjectionDetected() {
            return guardResult.violation().map(v -> v == ViolationType.PROMPT_INJECTION).orElse(false);
        }
    }
}
