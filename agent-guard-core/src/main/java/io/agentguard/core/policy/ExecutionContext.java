package io.agentguard.core.policy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable runtime context passed with a tool call to enable context-aware
 * policy evaluation.
 *
 * <p>Context-aware policies allow rules like:
 * <ul>
 *   <li>"Allow {@code debug_tool} only in {@code DEV}"</li>
 *   <li>"Require consent for {@code deploy_tool} in {@code STAGING} but block in {@code PROD}"</li>
 *   <li>"Allow {@code admin_reset} only for users with tag {@code role=admin}"</li>
 * </ul>
 *
 * <pre>{@code
 * // Attach context to a tool call
 * ToolCall call = ToolCall.builder("id-1", "deploy_tool")
 *     .context(ExecutionContext.prod())
 *     .build();
 *
 * // Or with custom tags
 * ToolCall call = ToolCall.builder("id-2", "admin_reset")
 *     .context(ExecutionContext.builder()
 *         .environment(Environment.PROD)
 *         .userId("user-007")
 *         .tag("role", "admin")
 *         .build())
 *     .build();
 * }</pre>
 *
 * <p>This class is <strong>immutable and thread-safe</strong>.
 */
public final class ExecutionContext {

    /**
     * Deployment environment — the most common axis for context-aware rules.
     */
    public enum Environment {
        DEV,
        STAGING,
        PROD;

        /**
         * Case-insensitive parse, returns {@code PROD} for unknown values.
         */
        public static Environment fromString(String s) {
            if (s == null) return PROD;
            return switch (s.trim().toUpperCase()) {
                case "DEV", "DEVELOPMENT", "LOCAL" -> DEV;
                case "STAGING", "STAGE", "TEST" -> STAGING;
                default -> PROD;
            };
        }
    }

    private final Environment environment;
    private final String userId;
    private final String workspaceId;
    private final String runId;
    private final Map<String, String> tags;

    private ExecutionContext(Builder builder) {
        this.environment = builder.environment != null ? builder.environment : Environment.PROD;
        this.userId = builder.userId;
        this.workspaceId = builder.workspaceId;
        this.runId = builder.runId;
        this.tags = Collections.unmodifiableMap(new HashMap<>(builder.tags));
    }

    // ─── Factory shortcuts ────────────────────────────────────────────────────

    /**
     * Production context with no user/workspace scope.
     */
    public static ExecutionContext prod() {
        return new Builder().environment(Environment.PROD).build();
    }

    /**
     * Staging context with no user/workspace scope.
     */
    public static ExecutionContext staging() {
        return new Builder().environment(Environment.STAGING).build();
    }

    /**
     * Development context with no user/workspace scope.
     */
    public static ExecutionContext dev() {
        return new Builder().environment(Environment.DEV).build();
    }

    /**
     * Context from environment variable {@code AGENT_GUARD_ENV} (falls back to PROD).
     */
    public static ExecutionContext fromEnvironment() {
        String env = System.getenv("AGENT_GUARD_ENV");
        return new Builder().environment(Environment.fromString(env)).build();
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    /**
     * Current deployment environment. Never null — defaults to {@link Environment#PROD}.
     */
    public Environment environment() {
        return environment;
    }

    /**
     * The unique run identifier, if this context is scoped to a specific agent run.
     */
    public Optional<String> runId() {
        return Optional.ofNullable(runId);
    }

    /**
     * The user making the request, if scoped.
     */
    public Optional<String> userId() {
        return Optional.ofNullable(userId);
    }

    /**
     * The workspace making the request, if scoped.
     */
    public Optional<String> workspaceId() {
        return Optional.ofNullable(workspaceId);
    }

    /**
     * Arbitrary key-value tags for custom context matching. Never null.
     */
    public Map<String, String> tags() {
        return tags;
    }

    /**
     * Returns the value of a specific tag, if present.
     */
    public Optional<String> tag(String key) {
        return Optional.ofNullable(tags.get(Objects.requireNonNull(key)));
    }

    /**
     * @return {@code true} if running in {@link Environment#DEV}.
     */
    public boolean isDev() {
        return environment == Environment.DEV;
    }

    /**
     * @return {@code true} if running in {@link Environment#STAGING}.
     */
    public boolean isStaging() {
        return environment == Environment.STAGING;
    }

    /**
     * @return {@code true} if running in {@link Environment#PROD}.
     */
    public boolean isProd() {
        return environment == Environment.PROD;
    }

    // ─── Builder ─────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Environment environment;
        private String userId;
        private String workspaceId;
        private String runId;
        private final Map<String, String> tags = new HashMap<>();

        public Builder environment(Environment env) {
            this.environment = Objects.requireNonNull(env);
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        /**
         * Scopes this context to a specific agent run for per-run state isolation.
         */
        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder tag(String key, String value) {
            this.tags.put(
                    Objects.requireNonNull(key, "tag key"),
                    Objects.requireNonNull(value, "tag value"));
            return this;
        }

        public ExecutionContext build() {
            return new ExecutionContext(this);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ExecutionContext{env=").append(environment);
        if (runId != null) sb.append(", run=").append(runId);
        if (userId != null) sb.append(", user=").append(userId);
        if (workspaceId != null) sb.append(", workspace=").append(workspaceId);
        if (!tags.isEmpty()) sb.append(", tags=").append(tags);
        return sb.append('}').toString();
    }
}
