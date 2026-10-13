package io.eranalytics.pipeline.webapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.eranalytics.pipeline.webapi.config.FrontendResourceConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        properties = {
            "er.web-api.enabled=true",
            "er.web-api.frontend-location=classpath:/webapi/frontend-test/"
        })
@ContextConfiguration(
        classes = {
            FrontendResourceConfiguration.class,
            FrontendStaticResourcesTest.ApiEndpoint.class
        })
class FrontendStaticResourcesTest {
    @Autowired MockMvc http;

    @Test
    void servesIndexForHomeAndBrowserRouteRefresh() throws Exception {
        http.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        for (String path : java.util.List.of("/index.html", "/questions/receipt", "/collection")) {
            http.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(
                            content()
                                    .string(org.hamcrest.Matchers.containsString("Test frontend")));
        }
    }

    @Test
    void keepsApisAndMissingAssetsOutOfHtmlFallback() throws Exception {
        http.perform(get("/api/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        http.perform(get("/api/missing")).andExpect(status().isNotFound());
        http.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
        http.perform(get("/assets/app.js")).andExpect(status().isOk());
    }

    @RestController
    static class ApiEndpoint {
        @GetMapping("/api/test")
        Map<String, Boolean> test() {
            return Map.of("ok", true);
        }
    }
}
