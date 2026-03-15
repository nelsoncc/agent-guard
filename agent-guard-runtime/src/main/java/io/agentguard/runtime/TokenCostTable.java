package io.agentguard.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Maps model identifiers to per-token pricing and converts token counts into
 * USD cost estimates (Issue #7).
 *
 * <p>Prices are stored as cost-per-million-tokens ($/MTok) and applied with
 * {@link MathContext#DECIMAL128} precision. All costs are expressed in USD.
 *
 * <h2>Built-in models (Issue #6)</h2>
 * <ul>
 *   <li>OpenAI: {@code gpt-4o}, {@code gpt-4o-mini}, {@code gpt-4-turbo}, {@code gpt-3.5-turbo}</li>
 *   <li>Anthropic: {@code claude-3-5-sonnet}, {@code claude-3-5-haiku}, {@code claude-3-opus}</li>
 *   <li>Google: {@code gemini-1.5-pro}, {@code gemini-1.5-flash}</li>
 *   <li>Ollama: any {@code ollama/*} prefix → zero cost (local inference)</li>
 * </ul>
 *
 * <h2>Extensibility</h2>
 * <pre>{@code
 * TokenCostTable table = TokenCostTable.defaults()
 *     .withModel("my-fine-tuned-gpt4", 10.00, 30.00);  // $/MTok in, $/MTok out
 * }</pre>
 *
 * <p>Unknown models return {@link BigDecimal#ZERO} cost and log a one-time warning.
 */
public final class TokenCostTable {

    private static final Logger log = LoggerFactory.getLogger(TokenCostTable.class);
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    /**
     * Per-model pricing entry. All prices in USD per million tokens.
     */
    record ModelPrice(BigDecimal inputPerMTok, BigDecimal outputPerMTok) {
        ModelPrice(double inputPerMTok, double outputPerMTok) {
            this(BigDecimal.valueOf(inputPerMTok), BigDecimal.valueOf(outputPerMTok));
        }
    }

    private final Map<String, ModelPrice> prices;

    private TokenCostTable(Map<String, ModelPrice> prices) {
        this.prices = Map.copyOf(prices);
    }

    // ─── Factory ─────────────────────────────────────────────────────────────

    /**
     * Returns a table pre-loaded with all built-in model prices.
     */
    public static TokenCostTable defaults() {
        Map<String, ModelPrice> m = new HashMap<>();

        // ── OpenAI ───────────────────────────────────────────────────────────
        m.put("gpt-4o", new ModelPrice(2.50, 10.00));
        m.put("gpt-4o-2024-11-20", new ModelPrice(2.50, 10.00));
        m.put("gpt-4o-mini", new ModelPrice(0.15, 0.60));
        m.put("gpt-4o-mini-2024-07-18", new ModelPrice(0.15, 0.60));
        m.put("gpt-4-turbo", new ModelPrice(10.00, 30.00));
        m.put("gpt-4-turbo-preview", new ModelPrice(10.00, 30.00));
        m.put("gpt-3.5-turbo", new ModelPrice(0.50, 1.50));
        m.put("gpt-3.5-turbo-0125", new ModelPrice(0.50, 1.50));

        // ── Anthropic ─────────────────────────────────────────────────────────
        m.put("claude-3-5-sonnet-20241022", new ModelPrice(3.00, 15.00));
        m.put("claude-3-5-sonnet", new ModelPrice(3.00, 15.00));
        m.put("claude-3-5-haiku-20241022", new ModelPrice(0.80, 4.00));
        m.put("claude-3-5-haiku", new ModelPrice(0.80, 4.00));
        m.put("claude-3-opus-20240229", new ModelPrice(15.00, 75.00));
        m.put("claude-3-opus", new ModelPrice(15.00, 75.00));
        m.put("claude-3-sonnet", new ModelPrice(3.00, 15.00));
        m.put("claude-3-haiku", new ModelPrice(0.25, 1.25));

        // ── Google ────────────────────────────────────────────────────────────
        m.put("gemini-1.5-pro", new ModelPrice(1.25, 5.00));
        m.put("gemini-1.5-pro-latest", new ModelPrice(1.25, 5.00));
        m.put("gemini-1.5-flash", new ModelPrice(0.075, 0.30));
        m.put("gemini-1.5-flash-latest", new ModelPrice(0.075, 0.30));
        m.put("gemini-2.0-flash", new ModelPrice(0.10, 0.40));

        // ── Ollama (local — zero cost) ─────────────────────────────────────────
        // Handled specially via prefix match in priceFor()

        return new TokenCostTable(m);
    }

    // ─── Fluent customisation ─────────────────────────────────────────────────

    /**
     * Returns a new table with an additional (or overriding) model entry.
     *
     * @param modelId       case-insensitive model identifier
     * @param inputPerMTok  USD cost per million input tokens
     * @param outputPerMTok USD cost per million output tokens
     */
    public TokenCostTable withModel(String modelId, double inputPerMTok, double outputPerMTok) {
        Objects.requireNonNull(modelId, "modelId");
        Map<String, ModelPrice> copy = new HashMap<>(this.prices);
        copy.put(normalise(modelId), new ModelPrice(inputPerMTok, outputPerMTok));
        return new TokenCostTable(copy);
    }

    // ─── Cost calculation (Issue #7) ─────────────────────────────────────────

    /**
     * Calculates the total USD cost for the given token counts.
     *
     * @param inputTokens  number of prompt/input tokens
     * @param outputTokens number of completion/output tokens
     * @param modelId      model identifier (case-insensitive)
     * @return estimated cost in USD; {@link BigDecimal#ZERO} for unknown models
     */
    public BigDecimal calculateCost(long inputTokens, long outputTokens, String modelId) {
        ModelPrice price = priceFor(modelId);
        if (price == null) return BigDecimal.ZERO;

        BigDecimal inputCost = price.inputPerMTok()
                .multiply(BigDecimal.valueOf(inputTokens), MC)
                .divide(ONE_MILLION, MC);
        BigDecimal outputCost = price.outputPerMTok()
                .multiply(BigDecimal.valueOf(outputTokens), MC)
                .divide(ONE_MILLION, MC);

        return inputCost.add(outputCost, MC).setScale(10, RoundingMode.HALF_UP);
    }

    /**
     * Returns the input price per million tokens for the given model,
     * or {@link BigDecimal#ZERO} if unknown.
     */
    public BigDecimal inputPricePerMTok(String modelId) {
        ModelPrice p = priceFor(modelId);
        return p != null ? p.inputPerMTok() : BigDecimal.ZERO;
    }

    /**
     * Returns the output price per million tokens for the given model,
     * or {@link BigDecimal#ZERO} if unknown.
     */
    public BigDecimal outputPricePerMTok(String modelId) {
        ModelPrice p = priceFor(modelId);
        return p != null ? p.outputPerMTok() : BigDecimal.ZERO;
    }

    /**
     * Returns {@code true} if the model is known to this table.
     */
    public boolean hasModel(String modelId) {
        return priceFor(modelId) != null;
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private ModelPrice priceFor(String modelId) {
        if (modelId == null) return null;
        String key = normalise(modelId);

        // Exact match first
        ModelPrice exact = prices.get(key);
        if (exact != null) return exact;

        // Ollama prefix — local inference is free
        if (key.startsWith("ollama/") || key.startsWith("ollama:")) {
            return new ModelPrice(0.0, 0.0);
        }

        // Prefix-based fuzzy match for versioned model names
        // e.g. "gpt-4o-2024-08-06" → matches "gpt-4o"
        for (Map.Entry<String, ModelPrice> entry : prices.entrySet()) {
            if (key.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        log.warn("[TokenCostTable] Unknown model '{}' — cost will be reported as $0.00. " +
                "Add it via TokenCostTable.withModel() to get accurate billing.", modelId);
        return null;
    }

    private static String normalise(String id) {
        return id.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
