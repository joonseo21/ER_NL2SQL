package io.eranalytics.pipeline.webapi.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface AgentClient {
    ObjectNode query(String question, JsonNode conditions);
}
