package io.github.hhagenbuch.blackbox.spring;

import io.github.hhagenbuch.agent.llm.LlmClient;
import io.github.hhagenbuch.agent.tools.AgentTool;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * Decorates the seams: every {@code LlmClient} and {@code AgentTool} bean is
 * wrapped with a recording proxy as it is created. Because {@code AgentTool}
 * beans are wrapped before the {@code ToolRegistry} that consumes them is built,
 * and the {@code LlmClient} before the {@code AgentLoop} that uses it, the whole
 * agent runs through the recorders — with zero changes to the target.
 *
 * <p><b>Ordering contract.</b> This runs at order {@code 0} so it applies early and
 * therefore wraps <em>innermost</em>. That matters when
 * <a href="https://github.com/hhagenbuch/agent-meter">agent-meter</a> is on the classpath:
 * its {@code LlmClientMeteringBeanPostProcessor} is {@link Ordered#LOWEST_PRECEDENCE}, so it
 * applies last and wraps <em>outermost</em>. The result is the ordering you want — metering
 * measures the whole call (including recording overhead), while the recorder captures the
 * request/response exactly as the delegate sees it. Declaring the order explicitly keeps
 * that from depending on bean-registration accident.
 */
public final class RecordingBeanPostProcessor implements BeanPostProcessor, Ordered {

    @Override
    public int getOrder() {
        return 0; // innermost — see the ordering contract above
    }

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
