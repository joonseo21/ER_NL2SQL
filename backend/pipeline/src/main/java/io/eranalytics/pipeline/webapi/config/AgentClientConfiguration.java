package io.eranalytics.pipeline.webapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.eranalytics.pipeline.webapi.client.AgentClient;
import io.eranalytics.pipeline.webapi.client.HttpAgentClient;
import io.eranalytics.pipeline.webapi.client.MockAgentClient;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class AgentClientConfiguration {
    @Bean
    @ConditionalOnProperty(
            name = "er.web-api.agent.mode",
            havingValue = "http",
            matchIfMissing = true)
    public AgentClient httpAgentClient(
            ObjectMapper json,
            @Value("${er.web-api.agent.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${AGENT_INTERNAL_TOKEN:}") String token,
            @Value("${er.web-api.agent.connect-timeout-ms:3000}") long connectTimeout,
            @Value("${er.web-api.agent.read-timeout-ms:330000}") long readTimeout) {
        return new HttpAgentClient(
                json,
                baseUrl,
                token,
                Duration.ofMillis(connectTimeout),
                Duration.ofMillis(readTimeout));
    }

    @Bean
    @ConditionalOnProperty(name = "er.web-api.agent.mode", havingValue = "mock")
    public AgentClient mockAgentClient(
            ObjectMapper json, @Value("${er.web-api.mock-response:answer}") String scenario)
            throws IOException {
        return new MockAgentClient(json, scenario);
    }
}
