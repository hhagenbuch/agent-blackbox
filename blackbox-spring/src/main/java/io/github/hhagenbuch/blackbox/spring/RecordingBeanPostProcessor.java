package io.github.hhagenbuch.blackbox.spring;

import io.github.hhagenbuch.agent.llm.LlmClient;
import io.github.hhagenbuch.agent.tools.AgentTool;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Decorates the seams: every {@code LlmClient} and {@code AgentTool} bean is
 * wrapped with a recording proxy as it is created. Because {@code AgentTool}
 * beans are wrapped before the {@code ToolRegistry} that consumes them is built,
 * and the {@code LlmClient} before the {@code AgentLoop} that uses it, the whole
 * agent runs through the recorders — with zero changes to the target.
 */
public final class RecordingBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof LlmClient client && !(bean instanceof RecordingLlmClient)) {
            return new RecordingLlmClient(client);
        }
        if (bean instanceof AgentTool tool && !(bean instanceof RecordingAgentTool)) {
            return new RecordingAgentTool(tool);
        }
        return bean;
    }
}
