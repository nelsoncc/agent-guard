package io.agentguard.core.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable policy that configures the prompt injection detection rules.
 *
 * <p>Agent Guard ships with a curated set of default rules covering the
 * most common injection patterns. You can extend or override them:
 *
 * <pre>{@code
 * InjectionGuardPolicy policy = InjectionGuardPolicy.defaultRules();  // recommended
 *
 * // Audit only — log detections but don't block (useful during roll-out)
 * InjectionGuardPolicy policy = InjectionGuardPolicy.auditMode();
 *
 * // Custom rules in addition to defaults
 * InjectionGuardPolicy policy = InjectionGuardPolicy.builder()
 *     .includeDefaultRules(true)
 *     .addRule(InjectionRule.pattern("my_secret_keyword", "Custom pattern"))
 *     .enforceMode(true)
 *     .build();
 * }</pre>
 *
 * <p>This class is <strong>immutable and thread-safe</strong>.
 */
public final class InjectionGuardPolicy {

    private final boolean enforceMode;        // false = audit only (log, don't block)
    private final boolean includeDefaultRules;
    private final List<InjectionRule> customRules;
    private final boolean scanToolInputs;
    private final boolean scanToolOutputs;    // scan LLM outputs fed back as tool results

    private InjectionGuardPolicy(Builder builder) {
        this.enforceMode = builder.enforceMode;
        this.includeDefaultRules = builder.includeDefaultRules;
        this.customRules = Collections.unmodifiableList(new ArrayList<>(builder.customRules));
        this.scanToolInputs = builder.scanToolInputs;
        this.scanToolOutputs = builder.scanToolOutputs;
    }

    // ─── Factory shortcuts ────────────────────────────────────────────────────

    /**
     * Default rules in enforce mode — blocks on any known injection pattern.
     */
    public static InjectionGuardPolicy defaultRules() {
        return new Builder().build();
    }

    /**
     * Audit mode — detections are logged and exported as OTel events but
     * execution is not blocked. Use during initial roll-out to measure
     * false-positive rate before enabling enforcement.
     */
    public static InjectionGuardPolicy auditMode() {
        return new Builder().enforceMode(false).build();
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    /**
     * If {@code true}, a detected injection causes the action to be
     * {@link io.agentguard.core.GuardStatus#BLOCKED}. If {@code false},
     * only a warning is logged/exported.
     */
    public boolean isEnforceMode() {
        return enforceMode;
    }

    /**
     * Whether the built-in injection detection rules are active.
     */
    public boolean includeDefaultRules() {
        return includeDefaultRules;
    }

    /**
     * User-supplied rules applied in addition to (or instead of) defaults.
     */
    public List<InjectionRule> customRules() {
        return customRules;
    }

    /**
     * Scan the arguments passed <em>into</em> tools for injected instructions.
     */
    public boolean scanToolInputs() {
        return scanToolInputs;
    }

    /**
     * Scan content returned by tools (which gets fed back to the LLM) for
     * injected instructions hidden in external data.
     */
    public boolean scanToolOutputs() {
        return scanToolOutputs;
    }

    // ─── Nested type: InjectionRule ───────────────────────────────────────────

    /**
     * A single injection detection rule.  Rules are evaluated as regular
     * expressions against the content being scanned.
     */
    public static final class InjectionRule {
        private final String pattern;
        private final String description;
        private final boolean caseSensitive;
        private final boolean exfiltration;

        private InjectionRule(String pattern, String description, boolean caseSensitive, boolean exfiltration) {
            this.pattern = Objects.requireNonNull(pattern, "pattern");
            this.description = Objects.requireNonNull(description, "description");
            this.caseSensitive = caseSensitive;
            this.exfiltration = exfiltration;
        }

        /**
         * Creates a case-insensitive injection rule.
         */
        public static InjectionRule pattern(String regex, String description) {
            return new InjectionRule(regex, description, false, false);
        }

        /**
         * Creates a rule with explicit case sensitivity.
         */
        public static InjectionRule pattern(String regex, String description, boolean caseSensitive) {
            return new InjectionRule(regex, description, caseSensitive, false);
        }

        /**
         * Creates a data-exfiltration rule (emits {@code DATA_EXFILTRATION} instead of {@code PROMPT_INJECTION}).
         */
        public static InjectionRule exfiltration(String regex, String description) {
            return new InjectionRule(regex, description, false, true);
        }

        public String pattern() {
            return pattern;
        }

        public String description() {
            return description;
        }

        public boolean caseSensitive() {
            return caseSensitive;
        }

        public boolean isExfiltration() {
            return exfiltration;
        }

        @Override
        public String toString() {
            return "InjectionRule{'" + description + "'" + (exfiltration ? " [exfiltration]" : "") + "}";
        }
    }

    // ─── Builder ─────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enforceMode = true;
        private boolean includeDefaultRules = true;
        private final List<InjectionRule> customRules = new ArrayList<>();
        private boolean scanToolInputs = true;
        private boolean scanToolOutputs = true;

        public Builder enforceMode(boolean enforce) {
            this.enforceMode = enforce;
            return this;
        }

        public Builder includeDefaultRules(boolean include) {
            this.includeDefaultRules = include;
            return this;
        }

        public Builder addRule(InjectionRule rule) {
            this.customRules.add(Objects.requireNonNull(rule));
            return this;
        }

        public Builder scanToolInputs(boolean scan) {
            this.scanToolInputs = scan;
            return this;
        }

        public Builder scanToolOutputs(boolean scan) {
            this.scanToolOutputs = scan;
            return this;
        }

        public InjectionGuardPolicy build() {
            return new InjectionGuardPolicy(this);
        }
    }

    @Override
    public String toString() {
        return "InjectionGuardPolicy{enforce=" + enforceMode
                + ", defaultRules=" + includeDefaultRules
                + ", customRules=" + customRules.size()
                + ", scanInputs=" + scanToolInputs
                + ", scanOutputs=" + scanToolOutputs + '}';
    }
}
