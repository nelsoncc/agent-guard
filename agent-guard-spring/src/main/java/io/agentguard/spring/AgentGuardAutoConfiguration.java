package io.agentguard.spring;

import io.agentguard.core.AgentGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for Agent Guard.
 *
 * <p>Automatically creates an {@link AgentGuard} bean when:
 * <ul>
 *   <li>{@code AgentGuard} is on the classpath</li>
 *   <li>{@code agent-guard.enabled} is not explicitly set to {@code false}</li>
 *   <li>No other {@code AgentGuard} bean is already defined</li>
 * </ul>
 *
 * <h2>Properties</h2>
 * <p>See {@link AgentGuardProperties} for all available {@code agent-guard.*} keys.
 *
 * <h2>Overriding</h2>
 * <p>To provide a custom guard, declare your own {@code @Bean AgentGuard} —
 * this auto-configuration will back off thanks to {@code @ConditionalOnMissingBean}.
 *
 * <h2>Spring Boot 4 compatibility</h2>
 * <p>This class is registered via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * and uses the modular auto-configuration pattern introduced in Spring Boot 3.x / 4.x.
 */
@AutoConfiguration
@ConditionalOnClass(AgentGuard.class)
@ConditionalOnProperty(prefix = "agent-guard", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(AgentGuardProperties.class)
public class AgentGuardAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentGuardAutoConfiguration.class);

    /**
     * Creates the default {@link AgentGuard} bean from configuration properties.
     *
     * @param properties the bound {@code agent-guard.*} properties
     * @return a fully-configured guard instance
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentGuard agentGuard(AgentGuardProperties properties) {
        if (!properties.isEnabled()) {
            log.info("[AgentGuardAutoConfiguration] disabled via agent-guard.enabled=false");
            return AgentGuard.builder().build();
        }
        log.info("[AgentGuardAutoConfiguration] Creating AgentGuard for service '{}'",
                properties.getServiceName());
        return AgentGuardBuilderFactory.build(properties);
    }
}
