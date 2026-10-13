package io.eranalytics.pipeline.webapi.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.webapi.config.InviteCodeFilter;
import io.eranalytics.pipeline.webapi.dto.WebApiRequests;
import io.eranalytics.pipeline.webapi.service.ConditionsService;
import io.eranalytics.pipeline.webapi.service.FeedbackService;
import io.eranalytics.pipeline.webapi.service.InviteService;
import io.eranalytics.pipeline.webapi.service.QuestionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class WebApiController {
    private final InviteService invites;
    private final ConditionsService conditions;
    private final QuestionService questions;
    private final FeedbackService feedback;

    @PostMapping("/invite/verify")
    public Map<String, Object> verify(@Valid @RequestBody WebApiRequests.Invite request) {
        return Map.of("valid", true, "remaining_today", invites.remaining(request.code()));
    }

    @GetMapping("/conditions")
    public ObjectNode conditions() {
        return conditions.list();
    }

    @PostMapping("/questions")
    public ResponseEntity<ObjectNode> question(
            @RequestAttribute(InviteCodeFilter.ATTRIBUTE) String code,
            @Valid @RequestBody WebApiRequests.Question request) {
        ObjectNode receipt = questions.ask(code, request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/questions/" + receipt.required("request_id").asText()))
                .body(receipt);
    }

    @GetMapping("/questions/{requestId}")
    public ObjectNode result(
            @RequestAttribute(InviteCodeFilter.ATTRIBUTE) String code,
            @PathVariable String requestId) {
        return questions.find(code, requestId);
    }

    @PostMapping("/questions/{logId}/feedback")
    public Map<String, Boolean> feedback(
            @RequestAttribute(InviteCodeFilter.ATTRIBUTE) String code,
            @PathVariable String logId,
            @Valid @RequestBody WebApiRequests.Feedback request) {
        feedback.save(code, logId, request);
        return Map.of("saved", true);
    }
}
