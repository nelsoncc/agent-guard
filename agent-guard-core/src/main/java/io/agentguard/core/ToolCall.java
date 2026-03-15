package io.agentguard.core;

import io.agentguard.core.policy.ExecutionContext;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a single tool invocation that the agent wants to perform.
 *
 * <p>A {@code ToolCall} is the primary unit evaluated by the guard:
 * the {@link io.agentguard.core.spi.ToolGuard} checks it against
 * all configured policies and returns a {@link GuardResult}.
 *
 * <p>This class is <strong>immutable and thread-safe</strong>.
 */
public final class ToolCall {

    private final String id;
    private final String toolName;
    private final Map<String, Object> arguments;
    private final String rawInput;              // Original serialised arguments (for injection scan)
    private final Instant timestamp;
    private final ExecutionContext context;     // Optional — for context-aware policy evaluation

    private ToolCall(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.toolName = Objects.requireNonNull(builder.toolName, "toolName");
        this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(builder.arguments));
        this.rawInput = builder.rawInput;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.context = builder.context;
    }

    // ─── Factory shortcuts ────────────────────────────────────────────────────

    public static ToolCall of(String id, String toolName, Map<String, Object> arguments) {
        return new Builder(id, toolName).arguments(arguments).build();
    }

    public static ToolCall of(String id, String toolName) {
        return new Builder(id, toolName).build();
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    /**
     * Unique identifier for this tool call (e.g., LangChain4j call id).
     */
    public String id() {
        return id;
    }

    /**
     * The name of the tool being called (e.g., {@code "web_search"}).
     */
    public String toolName() {
        return toolName;
    }

    /**
     * Structured arguments passed to the tool. May be empty but never null.
     */
    public Map<String, Object> arguments() {
        return arguments;
    }

    /**
     * Raw serialised input (JSON or plain text) for use by the injection scanner.
     * May be empty if the caller did not provide it.
     */
    public String rawInput() {
        return rawInput != null ? rawInput : "";
    }

    /**
     * When this tool call was created.
     */
    public Instant timestamp() {
        return timestamp;
    }

    /**
     * Optional execution context for context-aware policy evaluation.
     * If absent, the policy engine uses its default rules without environment filtering.
     */
    public Optional<ExecutionContext> context() {
        return Optional.ofNullable(context);
    }

    /**
     * Returns the run identifier from this call's context, if present.
     * Convenience accessor for {@code context().flatMap(ExecutionContext::runId)}.
     */
    public Optional<String> runId() {
        return context().flatMap(ExecutionContext::runId);
    }

    /**
     * Returns a copy of this tool call with the given run identifier injected into
     * the execution context.  All other fields are preserved unchanged.
     *
     * <p>Used by {@code DefaultAgentGuard} to propagate the current run identifier
     * to all guards in the chain without requiring the caller to set it explicitly.
     *
     * @param runId the run identifier to attach
     * @return a new {@code ToolCall} with the run identifier set
     */
    public ToolCall withRunId(String runId) {
        Objects.requireNonNull(runId, "runId");
        ExecutionContext enriched = context().map(ctx ->
                ExecutionContext.builder()
                        .environment(ctx.environment())
                        .runId(runId)
                        .userId(ctx.userId().orElse(null))
                        .workspaceId(ctx.workspaceId().orElse(null))
                        .build()
        ).orElse(ExecutionContext.builder().runId(runId).build());

        return new Builder(this.id, this.toolName)
                .arguments(this.arguments)
                .rawInput(this.rawInput)
                .timestamp(this.timestamp)
                .context(enriched)
                .build();
    }

    // ─── Builder ─────────────────────────────────────────────────────────────

    public static Builder builder(String id, String toolName) {
        return new Builder(id, toolName);
    }

    public static final class Builder {
        private final String id;
        private final String toolName;
        private Map<String, Object> arguments = new LinkedHashMap<>();
        private String rawInput;
        private Instant timestamp;
        private ExecutionContext context;

        private Builder(String id, String toolName) {
            this.id = id;
            this.toolName = toolName;
        }

        public Builder arguments(Map<String, Object> arguments) {
            this.arguments = Objects.requireNonNull(arguments);
            return this;
        }

        public Builder argument(String key, Object value) {
            this.arguments.put(key, value);
            return this;
        }

        public Builder rawInput(String rawInput) {
            this.rawInput = rawInput;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Attaches an {@link ExecutionContext} for context-aware policy evaluation.
         */
        public Builder context(ExecutionContext context) {
            this.context = context;
            return this;
        }

        public ToolCall build() {
            return new ToolCall(this);
        }
    }

    @Override
    public String toString() {
        return "ToolCall{id='" + id + "', tool='" + toolName + "', args=" + arguments + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ToolCall)) return false;
        ToolCall other = (ToolCall) o;
        return Objects.equals(id, other.id) && Objects.equals(toolName, other.toolName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, toolName);
    }
}
