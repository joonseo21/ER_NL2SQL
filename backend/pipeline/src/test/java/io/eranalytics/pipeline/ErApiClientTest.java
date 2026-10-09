package io.eranalytics.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import io.eranalytics.pipeline.erapi.ErApiClient;
import io.eranalytics.pipeline.erapi.RequestRateGate;
import io.eranalytics.pipeline.config.ErApiProperties;
import io.eranalytics.pipeline.collection.CollectorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.eranalytics.pipeline.erapi.ApiException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

class ErApiClientTest {
    private record Setup(MockRestServiceServer server, ErApiClient client, CollectorRepository repository) {}

    private Setup setup() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var repository = mock(CollectorRepository.class);
        var client = new ErApiClient(builder, new ObjectMapper(), mock(RequestRateGate.class), repository,
                new ErApiProperties("https://open-api.bser.io", "synthetic-key", 41, 1000.0));
        return new Setup(server, client, repository);
    }

    @Test
    void followsIntegerNextThroughTypedPageAndNormalizesLoggedEndpoint() {
        var setup = setup();
        setup.server().expect(requestTo("https://open-api.bser.io/v1/user/games/uid/42?next=200"))
                .andRespond(withSuccess("""
                        {"code":200,"next":180,"unknown":"kept outside DTO",
                         "userGames":[{"gameId":190,"seasonId":41,"matchingMode":3,
                         "matchingTeamMode":3,"versionMajor":4,"nickname":"synthetic"}]}
                        """, MediaType.APPLICATION_JSON));
        var page = setup.client().userGames("42", 200L);
        assertThat(page.next()).isEqualTo(180L);
        assertThat(page.userGames().getFirst().gameId()).isEqualTo(190L);
        verify(setup.repository()).logApiCall(eq("/v1/user/games/uid/{uid}"), eq(200), anyLong());
        setup.server().verify();
    }

    @Test
    void payload404IsExposedToCrawlerWithoutParsingMissingGames() {
        var setup = setup();
        setup.server().expect(requestTo("https://open-api.bser.io/v1/user/games/uid/42?next=1"))
                .andRespond(withSuccess("{\"code\":404}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> setup.client().userGames("42", 1L))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatusCode()).isEqualTo(404));
        verify(setup.repository()).logApiCall(eq("/v1/user/games/uid/{uid}"), eq(404), anyLong());
        setup.server().verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "\"200\"", "9223372036854775808", "null"})
    void rejectsLossyOrMissingGameIds(String value) {
        var setup = setup();
        setup.server().expect(requestTo("https://open-api.bser.io/v1/user/games/uid/42"))
                .andRespond(withSuccess("""
                        {"code":200,"userGames":[{"gameId":%s,"seasonId":41,"matchingMode":3,
                        "matchingTeamMode":3,"versionMajor":4}]}
                        """.formatted(value), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> setup.client().userGames("42", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid user games response");
        setup.server().verify();
    }

    @Test
    void initialRankingIsNotTruncatedToLegacy50() {
        var setup = setup();
        var json = new ObjectMapper().createObjectNode();
        json.put("code", 200);
        var ranks = json.putArray("topRanks");
        for (int i = 0; i < 1000; i++) ranks.addObject().put("nickname", "synthetic_" + i).put("mmr", 8000);
        setup.server().expect(requestTo("https://open-api.bser.io/v1/rank/top/41/3"))
                .andRespond(withSuccess(json.toString(), MediaType.APPLICATION_JSON));
        assertThat(setup.client().topUsers(41)).hasSize(1000);
        setup.server().verify();
    }

    @Test
    void malformedJsonCreatesOnlyOneCallLog() {
        var setup = setup();
        setup.server().expect(requestTo("https://open-api.bser.io/v1/games/200"))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> setup.client().gameParticipants("200")).isInstanceOf(IllegalStateException.class);
        verify(setup.repository()).logApiCall(eq("/v1/games/{gameId}"), eq(0), anyLong());
        setup.server().verify();
    }
    @Test
    void encodesUnicodeNicknameExactlyOnce() {
        String nickname = "한글日本漢字";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ErApiProperties properties = new ErApiProperties(
                "https://open-api.bser.io", "test-key", 41, 1000.0);
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
