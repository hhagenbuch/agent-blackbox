package io.github.hhagenbuch.blackbox.spring;

import io.github.hhagenbuch.agent.llm.LlmClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for agent-blackbox. Add the dependency to an agent service
 * built on the starter's seams and sessions are recorded — no code changes.
 * Active only when the starter is on the classpath and {@code blackbox.enabled}
 * is not false.
 */
@AutoConfiguration
@ConditionalOnClass(LlmClient.class)
@ConditionalOnProperty(prefix = "blackbox", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(BlackboxProperties.class)
public class BlackboxAutoConfiguration {

    @Bean
    public RecordingBeanPostProcessor blackboxRecordingBeanPostProcessor() {
        return new RecordingBeanPostProcessor();
    }

    @Bean
    public BlackboxWebFilter blackboxWebFilter(BlackboxProperties props) {
        return new BlackboxWebFilter(props);
    }
}
