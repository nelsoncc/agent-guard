package io.agentguard.runtime;

import io.agentguard.core.GuardResult;
import io.agentguard.core.GuardStatus;
import io.agentguard.core.ToolCall;
import io.agentguard.core.ViolationType;
import io.agentguard.core.exception.ToolDeniedException;
import io.agentguard.core.policy.ExecutionContext;
import io.agentguard.core.policy.ToolPolicy;
import io.agentguard.core.policy.ToolRisk;
import io.agentguard.core.spi.ConsentHandler;
import io.agentguard.core.spi.ToolGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runtime implementation of the Tool Policy Engine (Milestone 3, Issues #16–#19).
 *
 * <p>Translates a {@link ToolPolicy} decision into a {@link GuardResult}:
 *
 * <table border="1">
 *   <tr><th>ToolPolicy result</th><th>GuardResult</th></tr>
 *   <tr><td>ALLOWED</td><td>GuardResult.allowed()</td></tr>
 *   <tr><td>BLOCKED</td><td>GuardResult.blockedTool(..., TOOL_DENIED)</td></tr>
 *   <tr><td>REQUIRE_CONSENT (no handler)</td><td>BLOCKED (fail-safe)</td></tr>
 *   <tr><td>REQUIRE_CONSENT + handler approved</td><td>GuardResult.allowed()</td></tr>
 *   <tr><td>REQUIRE_CONSENT + handler denied</td><td>BLOCKED</td></tr>
 *   <tr><td>REQUIRE_CONSENT + handler timeout</td><td>BLOCKED (fail-safe)</td></tr>
 * </table>
 *
 * <p><strong>Context-aware evaluation (#19):</strong> If the {@link ToolCall} carries
 * an {@link ExecutionContext}, environment-scoped rules are evaluated first.
 *
 * <p><strong>Risk scoring (#17):</strong> Tools with {@link ToolRisk#CRITICAL} are
 * always blocked; {@link ToolRisk#HIGH} triggers consent. Both are enforced via
 * {@link ToolPolicy#evaluate(String, ExecutionContext)}.
 *
 * <p>This class is thread-safe.
 */
public final class ToolPolicyEngine implements ToolGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolPolicyEngine.class);

    /**
     * Default time to wait for a consent decision before failing closed.
     */
    public static final long DEFAULT_CONSENT_TIMEOUT_SECONDS = 300L; // 5 minutes

    private final ToolPolicy policy;
    private final ConsentHandler consentHandler;       // may be null
    private final long consentTimeoutSeconds;

    /**
     * Creates a {@code ToolPolicyEngine} without a consent handler.
     * Any {@code REQUIRE_CONSENT} result will be treated as {@code BLOCKED}.
     *
     * @param policy the tool policy to enforce
     */
    public ToolPolicyEngine(ToolPolicy policy) {
        this(policy, null, DEFAULT_CONSENT_TIMEOUT_SECONDS);
    }

    /**
     * Creates a {@code ToolPolicyEngine} with a consent handler and default timeout.
     *
     * @param policy         the tool policy to enforce
     * @param consentHandler handler to invoke for {@code REQUIRE_CONSENT} tools
     */
    public ToolPolicyEngine(ToolPolicy policy, ConsentHandler consentHandler) {
        this(policy, consentHandler, DEFAULT_CONSENT_TIMEOUT_SECONDS);
    }

    /**
     * Creates a fully configured {@code ToolPolicyEngine}.
     *
     * @param policy                the tool policy to enforce
     * @param consentHandler        handler to invoke for {@code REQUIRE_CONSENT} tools (may be null)
     * @param consentTimeoutSeconds max seconds to wait for a consent decision
     */
    public ToolPolicyEngine(
            ToolPolicy policy,
            ConsentHandler consentHandler,
            long consentTimeoutSeconds) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.consentHandler = consentHandler;
        this.consentTimeoutSeconds = consentTimeoutSeconds > 0
                ? consentTimeoutSeconds
                : DEFAULT_CONSENT_TIMEOUT_SECONDS;
    }

    @Override
    public GuardResult evaluate(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall");

        String toolName = toolCall.toolName();
        ExecutionContext context = toolCall.context().orElse(null);

        // Evaluate policy — context rules take priority if context is present
        GuardStatus status = policy.evaluate(toolName, context);
        ToolRisk risk = policy.effectiveRisk(toolName);

        log.debug("[ToolPolicyEngine] tool='{}' env={} → {} (risk={})",
                toolName,
                context != null ? context.environment() : "none",
                status, risk);

        return switch (status) {
            case ALLOWED -> {
                log.debug("[ToolPolicyEngine] ALLOW: '{}'", toolName);
                yield GuardResult.allowed();
            }
            case BLOCKED -> {
                String reason = buildBlockReason(toolName, risk, context);
                log.info("[ToolPolicyEngine] BLOCK: '{}' — {}", toolName, reason);
                yield GuardResult.blockedTool(toolName, ViolationType.TOOL_DENIED, reason);
            }
            case REQUIRE_CONSENT -> handleConsent(toolCall, risk);
        };
    }

    // ─── Consent flow (#18) ───────────────────────────────────────────────────

    private GuardResult handleConsent(ToolCall toolCall, ToolRisk risk) {
        String toolName = toolCall.toolName();

        if (consentHandler == null) {
            String reason = String.format(
                    "Tool '%s' requires human consent but no ConsentHandler is configured. " +
                            "Add .consentHandler(...) to AgentGuard.builder() to enable consent flow.",
                    toolName);
            log.warn("[ToolPolicyEngine] BLOCK (no consent handler): '{}'", toolName);
            return GuardResult.blockedTool(toolName, ViolationType.TOOL_REQUIRES_CONSENT, reason);
        }

        String reason = String.format("Tool '%s' requires human approval (risk=%s)", toolName, risk);
        log.info("[ToolPolicyEngine] CONSENT REQUEST: '{}' — awaiting human approval (timeout={}s)",
                toolName, consentTimeoutSeconds);

        try {
            CompletableFuture<Boolean> future = consentHandler.requestConsent(toolCall, reason);
            boolean approved = future.get(consentTimeoutSeconds, TimeUnit.SECONDS);

            if (approved) {
                log.info("[ToolPolicyEngine] CONSENT GRANTED: '{}'", toolName);
                return GuardResult.allowed();
            } else {
                log.info("[ToolPolicyEngine] CONSENT DENIED: '{}'", toolName);
                return GuardResult.blockedTool(
                        toolName,
                        ViolationType.TOOL_REQUIRES_CONSENT,
                        String.format("Human approval denied for tool '%s'", toolName));
            }

        } catch (TimeoutException e) {
            log.warn("[ToolPolicyEngine] CONSENT TIMEOUT: '{}' after {}s — blocking (fail-safe)",
                    toolName, consentTimeoutSeconds);
            return GuardResult.blockedTool(
                    toolName,
                    ViolationType.TOOL_REQUIRES_CONSENT,
                    String.format("Consent request for '%s' timed out after %ds",
                            toolName, consentTimeoutSeconds));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ToolPolicyEngine] CONSENT INTERRUPTED: '{}' — blocking (fail-safe)", toolName);
            return GuardResult.blockedTool(
                    toolName,
                    ViolationType.TOOL_REQUIRES_CONSENT,
                    "Consent request was interrupted for tool '" + toolName + "'");

        } catch (Exception e) {
            log.error("[ToolPolicyEngine] CONSENT ERROR: '{}' — blocking (fail-safe): {}",
                    toolName, e.getMessage(), e);
            return GuardResult.blockedTool(
                    toolName,
                    ViolationType.TOOL_REQUIRES_CONSENT,
                    "Consent handler error for tool '" + toolName + "': " + e.getMessage());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String buildBlockReason(String toolName, ToolRisk risk, ExecutionContext context) {
        String normalised = toolName.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder("Tool '").append(toolName).append("' is denied");

        if (policy.denylist().contains(normalised)) {
            sb.append(" (on denylist)");
        } else if (risk.isAtLeast(ToolRisk.CRITICAL)) {
            sb.append(" (risk=CRITICAL)");
        } else if (context != null && policy.contextRules().stream()
                .anyMatch(r -> r.matches(normalised, context)
                        && r.action() == io.agentguard.core.GuardStatus.BLOCKED)) {
            sb.append(" (context rule: env=").append(context.environment()).append(")");
        } else {
            sb.append(" (default=BLOCKED policy)");
        }

        return sb.toString();
    }

    // ─── Accessors (for testing / introspection) ──────────────────────────────

    /**
     * The wrapped policy.
     */
    public ToolPolicy policy() {
        return policy;
    }

    /**
     * The configured consent handler, or {@code null} if none.
     */
    public ConsentHandler consentHandler() {
        return consentHandler;
    }

    /**
     * Consent timeout in seconds.
     */
    public long consentTimeoutSeconds() {
        return consentTimeoutSeconds;
    }
}
