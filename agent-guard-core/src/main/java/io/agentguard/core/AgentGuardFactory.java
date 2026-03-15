package io.agentguard.core;

/**
 * SPI factory interface discovered via {@link java.util.ServiceLoader}.
 *
 * <p>The {@code agent-guard-runtime} module provides the concrete implementation
 * of this interface and registers it in
 * {@code META-INF/services/io.agentguard.core.AgentGuardFactory}.
 *
 * <p>This indirection keeps {@code agent-guard-core} free of any runtime
 * or framework dependencies while still allowing {@link AgentGuard#builder()}
 * to produce a fully-wired instance transparently.
 */
public interface AgentGuardFactory {

    /**
     * Creates a fully configured {@link AgentGuard} from the accumulated builder state.
     *
     * @param builder the builder holding all policy configuration
     * @return a ready-to-use, thread-safe {@code AgentGuard} instance
     */
    AgentGuard create(AgentGuard.Builder builder);
}
