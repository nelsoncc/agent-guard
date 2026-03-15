package io.agentguard.runtime;

import io.agentguard.core.GuardResult;
import io.agentguard.core.GuardStatus;
import io.agentguard.core.ToolCall;
import io.agentguard.core.ViolationType;
import io.agentguard.core.exception.PromptInjectionException;
import io.agentguard.core.policy.InjectionGuardPolicy;
import io.agentguard.core.policy.InjectionGuardPolicy.InjectionRule;
import io.agentguard.core.spi.ToolGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime implementation of the Prompt Injection Guard (Milestone 4, Issues #22–#26).
 *
 * <h2>What it detects</h2>
 * <p>The guard scans two surfaces on every tool call:
 * <ol>
 *   <li>{@link ToolCall#rawInput()} — the original serialised input string</li>
 *   <li>Each structured argument value converted to a string</li>
 * </ol>
 *
 * <p>Built-in default rules cover five attack categories (#23–#25):
 * <ul>
 *   <li><b>Ignore-instructions</b> (#23) — e.g. "Ignore previous instructions"</li>
 *   <li><b>Role confusion</b> (#25) — e.g. "Act as a DAN", "Pretend you are"</li>
 *   <li><b>Jailbreak keywords</b> (#25) — DAN, jailbreak, "do anything now"</li>
 *   <li><b>Embedded system tags</b> (#23) — {@code [SYSTEM]}, {@code <s>}</li>
 *   <li><b>Data exfiltration</b> (#24) — {@code mailto:}, suspicious URLs, bare email addresses</li>
 * </ul>
 *
 * <h2>Enforce vs. audit mode (#26)</h2>
 * <p>When {@link InjectionGuardPolicy#isEnforceMode()} is {@code true} (the default),
 * a detection returns {@link GuardStatus#BLOCKED} and throws
 * {@link PromptInjectionException}. When {@code false} (audit mode), the detection
 * is only logged and the call is {@link GuardStatus#ALLOWED} — useful during initial
 * roll-out to measure false-positive rate before enabling enforcement.
 *
 * <h2>Custom rules</h2>
 * <p>Rules in {@link InjectionGuardPolicy#customRules()} are compiled alongside the
 * defaults. Set {@link InjectionGuardPolicy.Builder#includeDefaultRules(boolean)} to
 * {@code false} to use only custom rules.
 *
 * <p>This class is <strong>thread-safe</strong>: all state is final after construction.
 */
public final class InjectionGuard implements ToolGuard {

    private static final Logger log = LoggerFactory.getLogger(InjectionGuard.class);

    // ─── Default rules (#23, #24, #25) ───────────────────────────────────────

    /**
     * Issue #23 — "ignore previous instructions" and close variants.
     * Covers: ignore/disregard/forget + qualifiers + target word.
     */
    static final InjectionRule RULE_IGNORE_INSTRUCTIONS = InjectionRule.pattern(
            "(?i)\\b(ignore|disregard|forget|override|bypass)\\b.{0,30}"
                    + "\\b(all\\s+|previous\\s+|the\\s+|your\\s+)?"
                    + "(instructions?|context|prompt|directives?|system|guidelines?|constraints?)",
            "Ignore-instructions injection");

    /**
     * Issue #25 — role confusion: "act as", "pretend you are", "behave like".
     */
    static final InjectionRule RULE_ROLE_CONFUSION = InjectionRule.pattern(
            "(?i)\\b(pretend|act|behave|roleplay|imagine|assume)\\b.{0,10}"
                    + "\\byou\\b.{0,10}\\b(are|were|can|have|as)\\b",
            "Role-confusion / act-as injection");

    /**
     * Issue #25 — jailbreak keywords: DAN, STAN, "do anything now", etc.
     */
    static final InjectionRule RULE_JAILBREAK_KEYWORDS = InjectionRule.pattern(
            "(?i)\\b(DAN|STAN|DUDE|KEVIN|AIM|jailbreak|jailbroken|"
                    + "do\\s+anything\\s+now|developer\\s+mode|god\\s+mode|"
                    + "unrestricted\\s+mode|jail\\s*break)\\b",
            "Jailbreak keyword");

    /**
     * Issue #23 — embedded pseudo-system tags designed to confuse the model context.
     * Matches: [SYSTEM], [INST], <s>, </s>, <<SYS>>, [/INST]
     */
    static final InjectionRule RULE_SYSTEM_TAGS = InjectionRule.pattern(
            "(?i)(\\[\\s*(SYSTEM|INST|SYS|HUMAN|ASSISTANT|USER)\\s*\\]"
                    + "|\\[/\\s*(INST|SYS)\\s*\\]"
                    + "|<<\\s*SYS\\s*>>|<\\|im_start\\|>|<\\|im_end\\|>"
                    + "|<\\s*/?\\s*s\\s*>)",
            "Embedded system-instruction tag");

    /**
     * Issue #24 — mailto: URIs, a classic data-exfiltration vector.
     */
    static final InjectionRule RULE_MAILTO = InjectionRule.exfiltration(
            "(?i)\\bmailto:[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}",
            "mailto: data-exfiltration URI");

    static final InjectionRule RULE_SUSPICIOUS_EMAIL = InjectionRule.exfiltration(
            "(?i)\\b[a-zA-Z0-9._%+\\-]{3,}@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,6}\\b",
            "Suspicious email address (possible data exfiltration)");

    static final InjectionRule RULE_SUSPICIOUS_URL = InjectionRule.exfiltration(
            "(?i)https?://(?!localhost|127\\.0\\.0\\.1|\\[::1\\])[a-zA-Z0-9.\\-]+"
                    + "\\.[a-zA-Z]{2,}(/[^\\s]*)?",
            "Unexpected external URL in tool argument (possible data exfiltration)");

    /**
     * All built-in rules in priority order.
     */
    static final List<InjectionRule> DEFAULT_RULES = List.of(
            RULE_IGNORE_INSTRUCTIONS,
            RULE_ROLE_CONFUSION,
            RULE_JAILBREAK_KEYWORDS,
            RULE_SYSTEM_TAGS,
            RULE_MAILTO,
            RULE_SUSPICIOUS_EMAIL,
            RULE_SUSPICIOUS_URL
    );

    // ─── Instance state ───────────────────────────────────────────────────────

    private final InjectionGuardPolicy policy;
    private final List<CompiledRule> compiledRules;   // pre-compiled at construction

    /**
     * Pre-compiled rule — avoids recompiling patterns on every evaluation.
     */
    record CompiledRule(Pattern pattern, String description, String rawRegex, boolean isExfiltration) {
    }

    /**
     * Creates an {@code InjectionGuard} from the given policy.
     * All regex patterns are compiled eagerly so failures surface at startup,
     * not at runtime.
     *
     * @param policy the injection detection policy; must not be null
     * @throws java.util.regex.PatternSyntaxException if any regex in the policy is invalid
     */
    public InjectionGuard(InjectionGuardPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.compiledRules = buildCompiledRules(policy);
        log.debug("[InjectionGuard] Initialised with {} compiled rules, enforce={}",
                compiledRules.size(), policy.isEnforceMode());
    }

    // ─── ToolGuard ────────────────────────────────────────────────────────────

    @Override
    public GuardResult evaluate(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall");

        if (!policy.scanToolInputs()) {
            return GuardResult.allowed();
        }

        // 1. Scan rawInput (the full serialised string — highest signal)
        String raw = toolCall.rawInput();
        if (!raw.isEmpty()) {
            GuardResult result = scanText(raw, toolCall, "rawInput");
            if (result != null) return result;
        }

        // 2. Scan each structured argument value converted to string
        for (var entry : toolCall.arguments().entrySet()) {
            String argValue = String.valueOf(entry.getValue());
            if (argValue.isBlank()) continue;
            GuardResult result = scanText(argValue, toolCall, "arg:" + entry.getKey());
            if (result != null) return result;
        }

        return GuardResult.allowed();
    }

    // ─── Scanning logic ───────────────────────────────────────────────────────

    /**
     * Scans {@code text} against all compiled rules.
     *
     * @return a blocked/audit result if a rule fires, {@code null} if clean
     */
    private GuardResult scanText(String text, ToolCall toolCall, String surface) {
        for (CompiledRule rule : compiledRules) {
            Matcher matcher = rule.pattern().matcher(text);
            if (matcher.find()) {
                String matched = matcher.group();
                return onDetection(toolCall, surface, rule, matched);
            }
        }
        return null;
    }

    private GuardResult onDetection(
            ToolCall toolCall, String surface, CompiledRule rule, String matchedText) {

        String reason = String.format(
                "Injection detected in '%s' on surface '%s': rule='%s' matched='%s'",
                toolCall.toolName(), surface, rule.description(), truncate(matchedText, 80));

        // Exfiltration rules get their own violation type for better observability
        ViolationType violationType = rule.isExfiltration()
                ? ViolationType.DATA_EXFILTRATION
                : ViolationType.PROMPT_INJECTION;

        if (policy.isEnforceMode()) {
            log.warn("[InjectionGuard] BLOCKED — {}", reason);
            GuardResult result = GuardResult.builder(io.agentguard.core.GuardStatus.BLOCKED)
                    .violation(violationType)
                    .blockReason(reason)
                    .toolName(toolCall.toolName())
                    .build();
            throw new PromptInjectionException(reason, result, rule.rawRegex(), rule.description());
        } else {
            // Audit mode (#26): log but allow
            log.warn("[InjectionGuard] AUDIT (not blocked) — {}", reason);
            return GuardResult.allowed();
        }
    }

    // ─── Rule compilation ─────────────────────────────────────────────────────

    private static List<CompiledRule> buildCompiledRules(InjectionGuardPolicy policy) {
        List<InjectionGuardPolicy.InjectionRule> rules = new ArrayList<>();

        if (policy.includeDefaultRules()) {
            rules.addAll(DEFAULT_RULES);
        }
        rules.addAll(policy.customRules());

        List<CompiledRule> compiled = new ArrayList<>(rules.size());
        for (InjectionGuardPolicy.InjectionRule rule : rules) {
            int flags = rule.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            compiled.add(new CompiledRule(
                    Pattern.compile(rule.pattern(), flags),
                    rule.description(),
                    rule.pattern(),
                    rule.isExfiltration()));
        }
        return List.copyOf(compiled);
    }

    // ─── Accessors (for testing / introspection) ──────────────────────────────

    /**
     * The configured policy.
     */
    public InjectionGuardPolicy policy() {
        return policy;
    }

    /**
     * The pre-compiled rules (default + custom).
     */
    public List<CompiledRule> compiledRules() {
        return compiledRules;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
