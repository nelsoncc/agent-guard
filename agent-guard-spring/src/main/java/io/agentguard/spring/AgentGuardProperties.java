package io.agentguard.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for Agent Guard Spring Boot auto-configuration.
 *
 * <p>Bind in {@code application.properties} or {@code application.yml}:
 * <pre>{@code
 * agent-guard.enabled=true
 * agent-guard.service-name=my-agent-service
 * agent-guard.fail-safe=FAIL_CLOSED
 *
 * # Budget
 * agent-guard.budget.per-run-usd=0.50
 * agent-guard.budget.per-hour-usd=2.00
 * agent-guard.budget.per-run-tokens=50000
 *
 * # Loop detection
 * agent-guard.loop.max-repeats=3
 * agent-guard.loop.window-size=10
 * agent-guard.loop.backoff=true
 *
 * # Tool policy
 * agent-guard.tool-policy.default=BLOCKED
 * agent-guard.tool-policy.allow=web_search,read_file,calculator
 * agent-guard.tool-policy.deny=delete_db,wipe_all
 * agent-guard.tool-policy.require-consent=send_email,post_to_slack
 *
 * # Injection guard
 * agent-guard.injection.enabled=true
 * agent-guard.injection.enforce=true
 *
 * # OTel
 * agent-guard.otel.enabled=true
 * }</pre>
 *
 * <p>When bound by Spring Boot's {@code @ConfigurationProperties}, this class is
 * consumed by {@link AgentGuardAutoConfiguration} to create an {@code AgentGuard} bean.
 */
@ConfigurationProperties(prefix = "agent-guard")
public class AgentGuardProperties {

    /**
     * Whether Agent Guard is enabled. Default: true.
     */
    private boolean enabled = true;

    /**
     * Logical service name for OTel tagging.
     */
    private String serviceName = "agent-guard";

    /**
     * Fail-safe mode: FAIL_CLOSED (default) or FAIL_OPEN.
     */
    private String failSafe = "FAIL_CLOSED";

    private Budget budget = new Budget();
    private Loop loop = new Loop();
    private ToolPolicyConfig toolPolicy = new ToolPolicyConfig();
    private InjectionConfig injection = new InjectionConfig();
    private OtelConfig otel = new OtelConfig();

    // ─── Nested config classes ────────────────────────────────────────────────

    public static class Budget {
        /**
         * Maximum cost per run in USD. null = no limit.
         */
        private BigDecimal perRunUsd;
        /**
         * Maximum cost per hour in USD (rolling window). null = no limit.
         */
        private BigDecimal perHourUsd;
        /**
         * Maximum cost per day in USD (rolling window). null = no limit.
         */
        private BigDecimal perDayUsd;
        /**
         * Maximum tokens per run. 0 = no limit.
         */
        private long perRunTokens = 0;

        public BigDecimal getPerRunUsd() {
            return perRunUsd;
        }

        public void setPerRunUsd(BigDecimal v) {
            this.perRunUsd = v;
        }

        public BigDecimal getPerHourUsd() {
            return perHourUsd;
        }

        public void setPerHourUsd(BigDecimal v) {
            this.perHourUsd = v;
        }

        public BigDecimal getPerDayUsd() {
            return perDayUsd;
        }

        public void setPerDayUsd(BigDecimal v) {
            this.perDayUsd = v;
        }

        public long getPerRunTokens() {
            return perRunTokens;
        }

        public void setPerRunTokens(long v) {
            this.perRunTokens = v;
        }
    }

    public static class Loop {
        /**
         * Max repeats before blocking. Default: 3.
         */
        private int maxRepeats = 3;
        /**
         * Sliding window size. Default: 10.
         */
        private int windowSize = 10;
        /**
         * Emit one warning before hard block. Default: true.
         */
        private boolean backoff = true;
        /**
         * Enable semantic (normalised) loop detection. Default: false.
         */
        private boolean semantic = false;

        public int getMaxRepeats() {
            return maxRepeats;
        }

        public void setMaxRepeats(int v) {
            this.maxRepeats = v;
        }

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int v) {
            this.windowSize = v;
        }

        public boolean isBackoff() {
            return backoff;
        }

        public void setBackoff(boolean v) {
            this.backoff = v;
        }

        public boolean isSemantic() {
            return semantic;
        }

        public void setSemantic(boolean v) {
            this.semantic = v;
        }
    }

    public static class ToolPolicyConfig {
        /**
         * Default action for unlisted tools: BLOCKED or ALLOWED. Default: BLOCKED.
         */
        private String defaultAction = "BLOCKED";
        /**
         * Tools explicitly allowed.
         */
        private List<String> allow = new ArrayList<>();
        /**
         * Tools explicitly denied.
         */
        private List<String> deny = new ArrayList<>();
        /**
         * Tools requiring human consent.
         */
        private List<String> requireConsent = new ArrayList<>();
        /**
         * Path to a YAML/JSON policy file (overrides allow/deny/requireConsent lists).
         */
        private String policyFile;

        public String getDefaultAction() {
            return defaultAction;
        }

        public void setDefaultAction(String v) {
            this.defaultAction = v;
        }

        public List<String> getAllow() {
            return allow;
        }

        public void setAllow(List<String> v) {
            this.allow = v;
        }

        public List<String> getDeny() {
            return deny;
        }

        public void setDeny(List<String> v) {
            this.deny = v;
        }

        public List<String> getRequireConsent() {
            return requireConsent;
        }

        public void setRequireConsent(List<String> v) {
            this.requireConsent = v;
        }

        public String getPolicyFile() {
            return policyFile;
        }

        public void setPolicyFile(String v) {
            this.policyFile = v;
        }
    }

    public static class InjectionConfig {
        /**
         * Enable injection guard. Default: true.
         */
        private boolean enabled = true;
        /**
         * Block on detection (true) or audit-only (false). Default: true.
         */
        private boolean enforce = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean v) {
            this.enabled = v;
        }

        public boolean isEnforce() {
            return enforce;
        }

        public void setEnforce(boolean v) {
            this.enforce = v;
        }
    }

    public static class OtelConfig {
        /**
         * Enable OTel integration if opentelemetry-api is present. Default: true.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean v) {
            this.enabled = v;
        }
    }

    // ─── Top-level accessors ─────────────────────────────────────────────────

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String v) {
        this.serviceName = v;
    }

    public String getFailSafe() {
        return failSafe;
    }

    public void setFailSafe(String v) {
        this.failSafe = v;
    }

    public Budget getBudget() {
        return budget;
    }

    public void setBudget(Budget v) {
        this.budget = v;
    }

    public Loop getLoop() {
        return loop;
    }

    public void setLoop(Loop v) {
        this.loop = v;
    }

    public ToolPolicyConfig getToolPolicy() {
        return toolPolicy;
    }

    public void setToolPolicy(ToolPolicyConfig v) {
        this.toolPolicy = v;
    }

    public InjectionConfig getInjection() {
        return injection;
    }

    public void setInjection(InjectionConfig v) {
        this.injection = v;
    }

    public OtelConfig getOtel() {
        return otel;
    }

    public void setOtel(OtelConfig v) {
        this.otel = v;
    }
}
