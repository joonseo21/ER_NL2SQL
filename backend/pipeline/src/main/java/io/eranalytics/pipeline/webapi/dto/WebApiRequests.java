package io.eranalytics.pipeline.webapi.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class WebApiRequests {
    private WebApiRequests() {}

    public record Invite(@NotBlank String code) {}

    public record Question(@NotBlank String question, JsonNode conditions) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Question from(JsonNode request) {
            if (!request.isObject()) {
                throw new IllegalArgumentException("Question must be an object");
            }
            JsonNode question = request.get("question");
            if (question == null || !question.isTextual()) {
                throw new IllegalArgumentException("Question must be a string");
            }
            JsonNode conditions = request.get("conditions");
            if (conditions != null && conditions.isNull()) {
                throw new IllegalArgumentException("Conditions cannot be explicit null");
            }
            return new Question(question.textValue(), conditions);
        }
    }

    public record Feedback(
            @NotBlank @Pattern(regexp = "like|dislike") String rating,
            @Size(max = 500) String comment) {}
}
