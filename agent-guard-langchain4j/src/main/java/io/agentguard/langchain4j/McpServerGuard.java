package io.agentguard.langchain4j;

import io.agentguard.core.AgentGuard;
import io.agentguard.core.GuardResult;
import io.agentguard.core.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * LangChain4j integration: guard wrapper for MCP (Model Context Protocol) tool
 * servers (Issue #35).
 *
 * <p>MCP servers expose tools that the agent can call. {@code McpServerGuard}
 * intercepts each tool invocation <em>before</em> it is forwarded to the MCP
 * server, evaluates it through the configured {@link AgentGuard}, and either
 * allows, blocks, or pauses (consent) the call.
 *
 * <h2>Usage with LangChain4j's McpClient</h2>
 * <pre>{@code
 * // Standard LangChain4j MCP invocation function
 * Function<McpToolRequest, McpToolResponse> mcpClient = McpClient.connect(serverUrl);
 *
 * // Wrap it with Agent Guard
 * AgentGuard guard = AgentGuard.builder()
 *     .toolPolicy(ToolPolicy.denyAll()
 *         .allow("read_resource")
 *         .requireConsent("write_resource")
 *         .deny("delete_resource")
 *         .build())
 *     .injectionGuard(InjectionGuardPolicy.defaultRules())
 *     .build();
 *
 * Function<McpToolRequest, McpToolResponse> guardedClient =
 *     McpServerGuard.wrap(mcpClient, guard);
 *
 * // The guard is now transparent to callers
 * McpToolResponse response = guardedClient.apply(request);
 * }</pre>
 *
 * <h2>Framework-agnostic design</h2>
 * <p>{@code McpServerGuard} uses a generic {@link Function}{@code <Map<String,Object>, Object>}
 * contract so it works with any MCP transport (LangChain4j, custom HTTP client, etc.)
 * without a hard compile dependency on LangChain4j.
 *
 * <p>This class is <strong>thread-safe</strong>.
 */
public final class McpServerGuard {

    private static final Logger log = LoggerFactory.getLogger(McpServerGuard.class);

    // ─── Static factories ─────────────────────────────────────────────────────

    /**
     * Wraps a generic MCP tool executor with Agent Guard protection.
     *
     * <p>The {@code executor} function receives a {@code Map<String, Object>}
     * containing at minimum:
     * <ul>
     *   <li>{@code "tool"} — the tool name (String)</li>
     *   <li>{@code "arguments"} — the tool arguments (Map or JSON string)</li>
     * </ul>
     *
     * @param toolName the MCP tool name this guard instance protects
     * @param executor the real MCP tool execution function
     * @param guard    the configured guard
     * @return a guarded executor with the same signature
     */
    public static Function<Map<String, Object>, Object> wrapTool(
            String toolName,
            Function<Map<String, Object>, Object> executor,
            AgentGuard guard) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(guard, "guard");
        return new McpToolExecutor(toolName, executor, guard);
    }

    /**
     * Creates a {@link McpServerGuard} instance that can guard multiple tools
     * on the same server.
     *
     * @param guard the configured guard
     * @return a {@code McpServerGuard} builder
     */
    public static McpServerGuard forServer(AgentGuard guard) {
        return new McpServerGuard(guard);
    }

    // ─── Instance ────────────────────────────────────────────────────────────

    private final AgentGuard guard;

    private McpServerGuard(AgentGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /**
     * Wraps a single MCP tool executor.
     *
     * @param toolName the tool name (used for policy lookup)
     * @param executor the real tool execution function
     * @return a guarded executor
     */
    public Function<Map<String, Object>, Object> tool(
            String toolName,
            Function<Map<String, Object>, Object> executor) {
        return wrapTool(toolName, executor, guard);
    }

    /**
     * Evaluates a pre-constructed tool call directly — useful when integrating
     * with frameworks that already produce a structured tool invocation object.
     *
     * @param toolName  the MCP tool name
     * @param arguments tool arguments as a key-value map
     * @param rawInput  the raw JSON/string representation (for injection scanning)
     * @return the guard result
     */
    public GuardResult evaluate(String toolName, Map<String, Object> arguments, String rawInput) {
        String callId = "mcp-" + UUID.randomUUID().toString().substring(0, 8);
        ToolCall call = ToolCall.builder(callId, toolName)
                .arguments(arguments)
                .rawInput(rawInput != null ? rawInput : "")
                .build();
        return guard.evaluateToolCall(call);
    }

    // ─── Inner executor ───────────────────────────────────────────────────────

    private static final class McpToolExecutor implements Function<Map<String, Object>, Object> {

        private static final AtomicLong counter = new AtomicLong(0);

        private final String toolName;
        private final Function<Map<String, Object>, Object> delegate;
        private final AgentGuard guard;

        McpToolExecutor(String toolName,
                        Function<Map<String, Object>, Object> delegate,
                        AgentGuard guard) {
            this.toolName = toolName;
            this.delegate = delegate;
            this.guard = guard;
        }

        @Override
        public Object apply(Map<String, Object> args) {
            String callId = "mcp-" + counter.incrementAndGet();
            String rawInput = args != null ? args.toString() : "";

            ToolCall toolCall = ToolCall.builder(callId, toolName)
                    .arguments(args != null ? args : Map.of())
                    .rawInput(rawInput)
                    .build();

            log.debug("[McpServerGuard] Evaluating MCP tool='{}' callId='{}'", toolName, callId);

            GuardResult result = guard.evaluateToolCall(toolCall);

            if (!result.isAllowed()) {
                String reason = result.blockReason()
                        .orElse("Guard blocked MCP tool: " + toolName);
                log.warn("[McpServerGuard] BLOCKED MCP tool='{}': {}", toolName, reason);
                throw new McpToolBlockedException(toolName, result, reason);
            }

            log.debug("[McpServerGuard] ALLOWED MCP tool='{}' — forwarding to server", toolName);
            return delegate.apply(args);
        }
    }

    // ─── Exception ────────────────────────────────────────────────────────────

    /**
     * Thrown when the guard blocks an MCP tool invocation.
     */
    public static final class McpToolBlockedException extends RuntimeException {
        private final GuardResult guardResult;
        private final String toolName;

        public McpToolBlockedException(String toolName, GuardResult result, String message) {
            super("Guard blocked MCP tool '" + toolName + "': " + message);
            this.toolName = toolName;
            this.guardResult = result;
        }

        public GuardResult guardResult() {
            return guardResult;
        }

        public String toolName() {
            return toolName;
        }
    }
}
