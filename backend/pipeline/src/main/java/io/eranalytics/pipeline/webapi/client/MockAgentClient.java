package io.eranalytics.pipeline.webapi.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Bundled contract fixtures only: never calls Gemini or executes generated SQL. */
@Component
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class MockAgentClient implements AgentClient {
    private final ObjectNode template;

    public MockAgentClient(
            ObjectMapper mapper, @Value("${er.web-api.mock-response:answer}") String scenario)
            throws IOException {
        if (!Set.of(
                        "answer",
                        "answer-without-summary",
                        "answer-empty-result",
                        "notice",
                        "clarification")
                .contains(scenario)) {
            throw new IllegalArgumentException("Unknown web API mock response scenario");
        }
        try (InputStream input =
                new ClassPathResource("webapi/samples/" + scenario + ".json").getInputStream()) {
            template = (ObjectNode) mapper.readTree(input);
        }
    }

    @Override
    public ObjectNode query(String question, JsonNode conditions) {
        ObjectNode response = template.deepCopy();
        response.put("question", question);
        response.put("log_id", UUID.randomUUID().toString());
        response.putNull("chart");
        response.putNull("conversation_context");
        ((com.fasterxml.jackson.databind.node.ArrayNode) response.required("warnings"))
                .add("개발용 가짜 에이전트 응답입니다. 질문과 조건으로 실제 데이터를 조회하지 않았습니다.");
        return response;
    }
}
