package io.eranalytics.pipeline.webapi.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.webapi.service.CollectionSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class CollectionSummaryController {
    private final CollectionSummaryService service;

    @GetMapping("/api/collection/summary")
    public ObjectNode summary() {
        return service.summary();
    }
}
