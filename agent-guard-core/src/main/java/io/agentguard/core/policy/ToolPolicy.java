package io.agentguard.core.policy;

import io.agentguard.core.GuardStatus;

import java.util.*;

/**
 * Immutable policy that governs which tools an agent is permitted to call.
 *
 * <p>Rules are evaluated in priority order:
 * <ol>
 *   <li><strong>Context rules</strong> — environment-specific overrides (highest priority)</li>
 *   <li><strong>Explicit deny</strong> — tool is on the denylist → BLOCKED</li>
 *   <li><strong>Explicit consent</strong> — tool requires human approval → REQUIRE_CONSENT</li>
 *   <li><strong>Explicit allow</strong> — tool is on the allowlist → ALLOWED</li>
 *   <li><strong>Risk-based default</strong> — CRITICAL → block, HIGH → consent</li>
 *   <li><strong>Default action</strong> — falls back to {@link #defaultAction()}</li>
 * </ol>
 *
 * <p>When {@link #defaultAction()} is {@link GuardStatus#BLOCKED} (deny-all mode),
 * only explicitly allowed tools will be permitted — the safest posture.
 *
 * <pre>{@code
 * ToolPolicy policy = ToolPolicy.denyAll()
 *     .allow("web_search")
 *     .allow("read_file")
 *     .requireConsent("send_email")
 *     .deny("delete_file")
 *     .withRisk("delete_file", ToolRisk.CRITICAL)
 *     // Context-aware: debug_tool allowed in DEV only
 *     .allowInEnvironment("debug_tool", ExecutionContext.Environment.DEV)
 *     .build();
 * }</pre>
 *
 * <p>This class is <strong>immutable and thread-safe</strong>.
 */
public final class ToolPolicy {

    private final GuardStatus defaultAction;
    private final Set<String> allowlist;
    private final Set<String> denylist;
    private final Set<String> consentRequired;
    private final Map<String, ToolRisk> riskOverrides;
    private final List<ContextRule> contextRules;   // evaluated before all other rules

    /**
     * A single context-aware rule that overrides the base policy for a specific
     * tool when the execution context matches.
     */
    public record ContextRule(
            String toolName,
            GuardStatus action,
            ExecutionContext.Environment environment   // null = any environment
    ) {
        public ContextRule {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(action, "action");
        }

        /**
         * @return {@code true} if this rule applies to the given context.
         */
        public boolean matches(String name, ExecutionContext ctx) {
            if (!toolName.equals(name)) return false;
            return environment == null || environment == ctx.environment();
        }
    }

    private ToolPolicy(Builder builder) {
        this.defaultAction = Objects.requireNonNull(builder.defaultAction);
        this.allowlist = Collections.unmodifiableSet(new HashSet<>(builder.allowlist));
        this.denylist = Collections.unmodifiableSet(new HashSet<>(builder.denylist));
        this.consentRequired = Collections.unmodifiableSet(new HashSet<>(builder.consentRequired));
        this.riskOverrides = Collections.unmodifiableMap(new HashMap<>(builder.riskOverrides));
        this.contextRules = Collections.unmodifiableList(new ArrayList<>(builder.contextRules));
    }

    // ─── Factory shortcuts ────────────────────────────────────────────────────

    /**
     * Starts a policy in <em>deny-all</em> mode: every tool is blocked unless
     * explicitly added to the allowlist. This is the recommended default for
     * production agents.
     */
    public static Builder denyAll() {
        return new Builder(GuardStatus.BLOCKED);
    }

    /**
     * Starts a policy in <em>allow-all</em> mode: every tool is allowed unless
     * explicitly denied. Useful during development or for read-only agents.
     */
    public static Builder allowAll() {
        return new Builder(GuardStatus.ALLOWED);
    }

    // ─── Evaluation ───────────────────────────────────────────────────────────

    /**
     * Evaluates this policy against a tool call, without any execution context.
     * Context rules are skipped; base rules apply.
     *
     * @param toolName the name of the tool being called
     * @return the guard status that should apply to this tool call
     */
    public GuardStatus evaluate(String toolName) {
        return evaluate(toolName, null);
    }

    /**
     * Evaluates this policy against a tool call with an optional execution context.
     *
     * <p>Evaluation order:
     * <ol>
     *   <li>Context rules (if context provided and a rule matches)</li>
     *   <li>Explicit deny</li>
     *   <li>Explicit consent</li>
     *   <li>Explicit allow</li>
     *   <li>Risk-based (CRITICAL → BLOCKED, HIGH → REQUIRE_CONSENT)</li>
     *   <li>Default action</li>
     * </ol>
     *
     * @param toolName the name of the tool being called
     * @param context  optional execution context; pass {@code null} to skip context rules
     * @return the guard status that should apply to this tool call
     */
    public GuardStatus evaluate(String toolName, ExecutionContext context) {
        Objects.requireNonNull(toolName, "toolName");
        String name = toolName.trim().toLowerCase(Locale.ROOT);

        // 1. Context rules — first match wins (most-specific rule listed first)
        if (context != null) {
            for (ContextRule rule : contextRules) {
                if (rule.matches(name, context)) {
                    return rule.action();
                }
            }
        }

        // 2-4. Base rules
        if (denylist.contains(name)) return GuardStatus.BLOCKED;
        if (consentRequired.contains(name)) return GuardStatus.REQUIRE_CONSENT;
        if (allowlist.contains(name)) return GuardStatus.ALLOWED;

        // 5. Risk-based default
        ToolRisk risk = riskOverrides.get(name);
        if (risk != null) {
            if (risk.isAtLeast(ToolRisk.CRITICAL)) return GuardStatus.BLOCKED;
            if (risk.isAtLeast(ToolRisk.HIGH)) return GuardStatus.REQUIRE_CONSENT;
        }

        // 6. Fallback
        return defaultAction;
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    /**
     * What happens to tools that match no explicit rule.
     */
    public GuardStatus defaultAction() {
        return defaultAction;
    }

    /**
     * Tools that are explicitly allowed.
     */
    public Set<String> allowlist() {
        return allowlist;
    }

    /**
     * Tools that are explicitly blocked.
     */
    public Set<String> denylist() {
        return denylist;
    }

    /**
     * Tools that require explicit human consent.
     */
    public Set<String> consentRequired() {
        return consentRequired;
    }

    /**
     * Per-tool risk overrides.
     */
    public Map<String, ToolRisk> riskOverrides() {
        return riskOverrides;
    }

    /**
     * Context-specific rules evaluated before all base rules.
     */
    public List<ContextRule> contextRules() {
        return contextRules;
    }

    /**
     * Returns the effective risk for a tool, either from an explicit override
     * or inferred from the default action.
     */
    public ToolRisk effectiveRisk(String toolName) {
        return riskOverrides.getOrDefault(
                toolName.trim().toLowerCase(Locale.ROOT),
                defaultAction == GuardStatus.BLOCKED ? ToolRisk.HIGH : ToolRisk.LOW);
    }

    // ─── Builder ─────────────────────────────────────────────────────────────

    public static final class Builder {
        private final GuardStatus defaultAction;
        private final Set<String> allowlist = new LinkedHashSet<>();
        private final Set<String> denylist = new LinkedHashSet<>();
        private final Set<String> consentRequired = new LinkedHashSet<>();
        private final Map<String, ToolRisk> riskOverrides = new LinkedHashMap<>();
        private final List<ContextRule> contextRules = new ArrayList<>();

        private Builder(GuardStatus defaultAction) {
            this.defaultAction = defaultAction;
        }

        /**
         * Explicitly allow a tool (overrides deny-all default).
         */
        public Builder allow(String toolName) {
            allowlist.add(normalise(toolName));
            return this;
        }

        /**
         * Explicitly deny a tool (overrides allow-all default).
         */
        public Builder deny(String toolName) {
            denylist.add(normalise(toolName));
            return this;
        }

        /**
         * Require human consent before this tool is executed.
         */
        public Builder requireConsent(String toolName) {
            consentRequired.add(normalise(toolName));
            return this;
        }

        /**
         * Assign a risk level to a tool for automatic tier-based enforcement.
         */
        public Builder withRisk(String toolName, ToolRisk risk) {
            riskOverrides.put(normalise(toolName), Objects.requireNonNull(risk));
            return this;
        }

        /**
         * Allow a tool only when the execution context matches the given environment.
         * Takes priority over base allow/deny/consent rules.
         *
         * <pre>{@code
         * .allowInEnvironment("debug_tool", ExecutionContext.Environment.DEV)
         * }</pre>
         */
        public Builder allowInEnvironment(String toolName, ExecutionContext.Environment env) {
            contextRules.add(new ContextRule(normalise(toolName), GuardStatus.ALLOWED, env));
            return this;
        }

        /**
         * Deny a tool only when the execution context matches the given environment.
         */
        public Builder denyInEnvironment(String toolName, ExecutionContext.Environment env) {
            contextRules.add(new ContextRule(normalise(toolName), GuardStatus.BLOCKED, env));
            return this;
        }

        /**
         * Require consent for a tool only in the given environment.
         */
        public Builder requireConsentInEnvironment(String toolName, ExecutionContext.Environment env) {
            contextRules.add(new ContextRule(normalise(toolName), GuardStatus.REQUIRE_CONSENT, env));
            return this;
        }

        /**
         * Add a fully custom context rule (e.g. for any-environment matches).
         */
        public Builder addContextRule(ContextRule rule) {
            contextRules.add(Objects.requireNonNull(rule));
            return this;
        }

        public ToolPolicy build() {
            // Validate no tool appears in both allow and deny
            Set<String> conflict = new HashSet<>(allowlist);
            conflict.retainAll(denylist);
            if (!conflict.isEmpty()) {
                throw new IllegalStateException(
                        "Tools cannot be in both allowlist and denylist: " + conflict);
            }
            return new ToolPolicy(this);
        }

        private static String normalise(String toolName) {
            return Objects.requireNonNull(toolName, "toolName")
                    .trim().toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public String toString() {
        return "ToolPolicy{default=" + defaultAction
                + ", allow=" + allowlist
                + ", deny=" + denylist
                + ", consent=" + consentRequired + '}';
    }
}
