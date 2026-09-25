package io.eranalytics.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

class ErApiClientTest {
    @Test
    void encodesUnicodeNicknameExactlyOnce() {
        String nickname = "한글日本漢字";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ErApiProperties properties = new ErApiProperties(
                "https://open-api.bser.io", "test-key", 41, 50, 1000.0);
        ErApiClient client = new ErApiClient(
                builder,
                new ObjectMapper(),
                new RequestRateGate(properties),
                mock(CollectorRepository.class),
                properties);

        server.expect(request -> {
            String rawQuery = request.getURI().getRawQuery();
            assertThat(rawQuery).startsWith("query=").doesNotContain("%25");
            assertThat(URLDecoder.decode(rawQuery.substring("query=".length()), StandardCharsets.UTF_8))
                    .isEqualTo(nickname);
        }).andRespond(withSuccess(
                "{\"code\":200,\"user\":{\"userId\":\"42\"}}",
                MediaType.APPLICATION_JSON));

        assertThat(client.get(
                "/v1/user/nickname?query={nickname}",
                Map.of("nickname", nickname),
                "/v1/user/nickname").path("user").path("userId").asText())
                .isEqualTo("42");
        server.verify();
    }
}
