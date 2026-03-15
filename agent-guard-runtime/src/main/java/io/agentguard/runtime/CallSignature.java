package io.agentguard.runtime;

import io.agentguard.core.ToolCall;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * An immutable fingerprint of a single tool call, used by {@link LoopDetector}
 * to identify repeated calls within the sliding window.
 *
 * <p>Two keys are computed for each call:
 * <ul>
 *   <li><strong>exactKey</strong> — tool name + sorted arguments, case-sensitive.
 *       Matches calls that are byte-for-byte identical.</li>
 *   <li><strong>semanticKey</strong> — tool name + sorted, normalised arguments
 *       (lowercase, trimmed, collapsed whitespace). Matches calls that differ only
 *       in trivial formatting, e.g.
 *       {@code web_search("weather Lisbon")} vs {@code web_search("Weather  Lisbon")}.</li>
 * </ul>
 *
 * <p>Package-private: consumers interact with {@link LoopDetector} only.
 */
final class CallSignature {

    private final String toolName;
    private final String exactKey;
    private final String semanticKey;

    private CallSignature(String toolName, String exactKey, String semanticKey) {
        this.toolName = toolName;
        this.exactKey = exactKey;
        this.semanticKey = semanticKey;
    }

    // ─── Factory ─────────────────────────────────────────────────────────────

    static CallSignature of(ToolCall call) {
        String exact = buildKey(call, false);
        String semantic = buildKey(call, true);
        return new CallSignature(call.toolName(), exact, semantic);
    }

    // ─── Accessors ────────────────────────────────────────────────────────────

    String toolName() {
        return toolName;
    }

    String exactKey() {
        return exactKey;
    }

    String semanticKey() {
        return semanticKey;
    }

    // ─── Key construction ─────────────────────────────────────────────────────

    /**
     * Builds a stable string key from a tool call's name and arguments.
     *
     * <p>Arguments are sorted by key name so that argument order does not affect
     * the key. When {@code semantic=true} values are normalised to remove trivial
     * differences (case, leading/trailing whitespace, collapsed internal whitespace).
     */
    private static String buildKey(ToolCall call, boolean semantic) {
        String name = semantic
                ? call.toolName().toLowerCase(Locale.ROOT).trim()
                : call.toolName();

        if (call.arguments().isEmpty()) {
            return name + "()";
        }

        // Use a TreeMap (or sort entries) so key order is deterministic
        Map<String, Object> sorted = new TreeMap<>(
                semantic ? String.CASE_INSENSITIVE_ORDER : String::compareTo);
        sorted.putAll(call.arguments());

        StringBuilder sb = new StringBuilder(name).append('(');
        sorted.forEach((k, v) -> {
            String argKey = semantic ? k.toLowerCase(Locale.ROOT).trim() : k;
            String argVal = semantic ? normalise(v) : stringify(v);
            sb.append(argKey).append('=').append(argVal).append(',');
        });
        // Replace trailing comma with closing paren
        sb.setCharAt(sb.length() - 1, ')');
        return sb.toString();
    }

    /**
     * Normalises a value for semantic comparison:
     * strings → lowercase + trimmed + collapsed whitespace; everything else → toString().
     */
    private static String normalise(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            return s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        }
        // Numeric and boolean values: stringify as-is (they don't have whitespace variants)
        return value.toString();
    }

    private static String stringify(Object value) {
        return value == null ? "null" : value.toString();
    }

    // ─── Object ───────────────────────────────────────────────────────────────

    @Override
    public String toString() {
        return "CallSignature{exact='" + exactKey + "'}";
    }
}
