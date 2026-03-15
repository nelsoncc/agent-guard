package io.agentguard.runtime;

import io.agentguard.core.GuardStatus;
import io.agentguard.core.policy.ExecutionContext;
import io.agentguard.core.policy.ToolPolicy;
import io.agentguard.core.policy.ToolRisk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Policy-as-code loader (Milestone 3, Issue #20).
 *
 * <p>Loads a {@link ToolPolicy} from a YAML or JSON file, enabling policies to be
 * defined in configuration rather than Java code. This is particularly useful for:
 * <ul>
 *   <li>Different policies per deployment environment (dev.yaml vs prod.yaml)</li>
 *   <li>GitOps workflows where policies live in version control</li>
 *   <li>Dynamic policy updates without redeployment</li>
 * </ul>
 *
 * <h2>YAML format ({@code agent-guard-policy.yaml})</h2>
 * <pre>{@code
 * # Default action when no explicit rule matches: BLOCKED | ALLOWED
 * default: BLOCKED
 *
 * allow:
 *   - web_search
 *   - read_file
 *
 * deny:
 *   - delete_db
 *   - wipe_everything
 *
 * requireConsent:
 *   - send_email
 *   - post_to_slack
 *
 * # Per-tool risk overrides: LOW | MEDIUM | HIGH | CRITICAL
 * risks:
 *   delete_file: CRITICAL
 *   send_email: HIGH
 *
 * # Context-aware rules — evaluated before base rules
 * contextRules:
 *   - tool: debug_tool
 *     action: ALLOW
 *     environment: DEV        # DEV | STAGING | PROD (optional, omit for any env)
 *   - tool: deploy_prod
 *     action: REQUIRE_CONSENT
 *     environment: STAGING
 *   - tool: deploy_prod
 *     action: BLOCKED
 *     environment: DEV
 * }</pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Load from classpath
 * ToolPolicy policy = PolicyFileLoader.fromClasspath("agent-guard-policy.yaml");
 *
 * // Load from filesystem
 * ToolPolicy policy = PolicyFileLoader.fromFile(Path.of("/etc/myapp/policy.yaml"));
 *
 * // Load from stream
 * ToolPolicy policy = new PolicyFileLoader().load(inputStream, Format.YAML);
 * }</pre>
 *
 * <p><strong>Dependency:</strong> Requires Jackson ({@code jackson-databind} and
 * {@code jackson-dataformat-yaml}) on the classpath. If Jackson is absent, a
 * {@link PolicyLoadException} is thrown with a clear message.
 */
public final class PolicyFileLoader {

    private static final Logger log = LoggerFactory.getLogger(PolicyFileLoader.class);

    /**
     * Supported policy file formats.
     */
    public enum Format {YAML, JSON}

    // ─── Static factory methods ───────────────────────────────────────────────

    /**
     * Loads a policy from the given classpath resource.
     * Format is inferred from the file extension ({@code .yaml}/{@code .yml} → YAML,
     * {@code .json} → JSON).
     *
     * @param resourcePath classpath resource path (e.g. {@code "agent-guard-policy.yaml"})
     * @return the loaded policy
     * @throws PolicyLoadException if the resource is missing or cannot be parsed
     */
    public static ToolPolicy fromClasspath(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        Format format = inferFormat(resourcePath);
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream stream = cl.getResourceAsStream(resourcePath);
        if (stream == null) {
            // try with leading slash stripped
            stream = PolicyFileLoader.class.getResourceAsStream("/" + resourcePath);
        }
        if (stream == null) {
            throw new PolicyLoadException(
                    "Policy file not found on classpath: " + resourcePath);
        }
        try (InputStream s = stream) {
            return new PolicyFileLoader().load(s, format);
        } catch (IOException e) {
            throw new PolicyLoadException("Failed to read classpath resource: " + resourcePath, e);
        }
    }

    /**
     * Loads a policy from the filesystem.
     * Format is inferred from the file extension.
     *
     * @param path path to the policy file
     * @return the loaded policy
     * @throws PolicyLoadException if the file cannot be read or parsed
     */
    public static ToolPolicy fromFile(Path path) {
        Objects.requireNonNull(path, "path");
        Format format = inferFormat(path.getFileName().toString());
        try (InputStream stream = Files.newInputStream(path)) {
            return new PolicyFileLoader().load(stream, format);
        } catch (IOException e) {
            throw new PolicyLoadException("Failed to read policy file: " + path, e);
        }
    }

    // ─── Instance method ──────────────────────────────────────────────────────

    /**
     * Loads a {@link ToolPolicy} from the given stream.
     *
     * @param stream the input stream (caller is responsible for closing it)
     * @param format YAML or JSON
     * @return the parsed policy
     * @throws PolicyLoadException if Jackson is missing or parsing fails
     */
    public ToolPolicy load(InputStream stream, Format format) {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(format, "format");
        try {
            Object mapper = createMapper(format);
            @SuppressWarnings("unchecked")
            Map<String, Object> root = invokeReadValue(mapper, stream);
            return buildPolicy(root);
        } catch (PolicyLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new PolicyLoadException("Failed to parse policy file (" + format + "): " + e.getMessage(), e);
        }
    }

    // ─── Reflection-based Jackson invocation ─────────────────────────────────
    // Using reflection keeps agent-guard-core free of Jackson at compile time.
    // Jackson is declared as `optional` in the runtime POM — it must be present at runtime.

    private Object createMapper(Format format) {
        String mapperClass = format == Format.YAML
                ? "com.fasterxml.jackson.dataformat.yaml.YAMLMapper"
                : "com.fasterxml.jackson.databind.ObjectMapper";
        try {
            Class<?> cls = Class.forName(mapperClass);
            return cls.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            String dep = format == Format.YAML
                    ? "jackson-dataformat-yaml"
                    : "jackson-databind";
            throw new PolicyLoadException(
                    "Jackson is required for policy file loading. " +
                            "Add '" + dep + "' to your dependencies. " +
                            "Original error: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new PolicyLoadException("Failed to create Jackson mapper: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeReadValue(Object mapper, InputStream stream) throws Exception {
        Class<?> mapperClass = mapper.getClass();
        // Walk up the hierarchy to find readValue(InputStream, Class)
        while (mapperClass != null) {
            try {
                var method = mapperClass.getDeclaredMethod("readValue", InputStream.class, Class.class);
                method.setAccessible(true);
                return (Map<String, Object>) method.invoke(mapper, stream, Map.class);
            } catch (NoSuchMethodException ignored) {
                mapperClass = mapperClass.getSuperclass();
            }
        }
        throw new PolicyLoadException("Could not find readValue method on Jackson mapper");
    }

    // ─── Policy builder from parsed map ──────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ToolPolicy buildPolicy(Map<String, Object> root) {
        if (root == null || root.isEmpty()) {
            throw new PolicyLoadException("Policy file is empty or could not be parsed");
        }

        // Default action
        String defaultStr = getString(root, "default", "BLOCKED");
        ToolPolicy.Builder builder = switch (defaultStr.toUpperCase()) {
            case "ALLOWED", "ALLOW" -> ToolPolicy.allowAll();
            default -> ToolPolicy.denyAll();
        };

        log.debug("[PolicyFileLoader] default={}", defaultStr);

        // Allowlist
        List<String> allow = getStringList(root, "allow");
        allow.forEach(t -> {
            builder.allow(t);
            log.debug("[PolicyFileLoader] allow: {}", t);
        });

        // Denylist
        List<String> deny = getStringList(root, "deny");
        deny.forEach(t -> {
            builder.deny(t);
            log.debug("[PolicyFileLoader] deny: {}", t);
        });

        // Consent list
        List<String> consent = getStringList(root, "requireConsent");
        consent.forEach(t -> {
            builder.requireConsent(t);
            log.debug("[PolicyFileLoader] requireConsent: {}", t);
        });

        // Risk overrides
        Object risksObj = root.get("risks");
        if (risksObj instanceof Map<?, ?> risks) {
            risks.forEach((k, v) -> {
                if (k instanceof String toolName && v instanceof String riskStr) {
                    try {
                        ToolRisk risk = ToolRisk.valueOf(riskStr.toUpperCase());
                        builder.withRisk(toolName, risk);
                        log.debug("[PolicyFileLoader] risk: {}={}", toolName, risk);
                    } catch (IllegalArgumentException e) {
                        log.warn("[PolicyFileLoader] Unknown risk level '{}' for tool '{}', skipping", riskStr, toolName);
                    }
                }
            });
        }

        // Context rules
        Object ctxObj = root.get("contextRules");
        if (ctxObj instanceof List<?> ctxList) {
            for (Object ruleObj : ctxList) {
                if (ruleObj instanceof Map<?, ?> rule) {
                    parseContextRule(rule, builder);
                }
            }
        }

        ToolPolicy policy = builder.build();
        log.info("[PolicyFileLoader] Loaded policy: {}", policy);
        return policy;
    }

    @SuppressWarnings("unchecked")
    private void parseContextRule(Map<?, ?> rule, ToolPolicy.Builder builder) {
        String tool = getStr(rule, "tool");
        String actionStr = getStr(rule, "action");
        String envStr = getStr(rule, "environment");

        if (tool == null || actionStr == null) {
            log.warn("[PolicyFileLoader] Context rule missing 'tool' or 'action', skipping: {}", rule);
            return;
        }

        GuardStatus action;
        try {
            action = switch (actionStr.toUpperCase()) {
                case "ALLOW", "ALLOWED" -> GuardStatus.ALLOWED;
                case "BLOCK", "BLOCKED", "DENY" -> GuardStatus.BLOCKED;
                case "REQUIRE_CONSENT", "CONSENT" -> GuardStatus.REQUIRE_CONSENT;
                default -> throw new IllegalArgumentException("Unknown action: " + actionStr);
            };
        } catch (IllegalArgumentException e) {
            log.warn("[PolicyFileLoader] Unknown action '{}' in context rule, skipping", actionStr);
            return;
        }

        ExecutionContext.Environment env = envStr != null
                ? ExecutionContext.Environment.fromString(envStr)
                : null;

        ToolPolicy.ContextRule contextRule = new ToolPolicy.ContextRule(
                tool.trim().toLowerCase(java.util.Locale.ROOT), action, env);
        builder.addContextRule(contextRule);
        log.debug("[PolicyFileLoader] contextRule: tool={} action={} env={}", tool, action, env);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val instanceof String s ? s : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(s -> s instanceof String)
                    .map(s -> (String) s)
                    .toList();
        }
        return List.of();
    }

    private static String getStr(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val instanceof String s ? s : null;
    }

    private static Format inferFormat(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".json")) return Format.JSON;
        return Format.YAML; // default for .yaml, .yml, or anything else
    }

    // ─── Exception ───────────────────────────────────────────────────────────

    /**
     * Thrown when a policy file cannot be loaded or parsed.
     */
    public static final class PolicyLoadException extends RuntimeException {
        public PolicyLoadException(String message) {
            super(message);
        }

        public PolicyLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
