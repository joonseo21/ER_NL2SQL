package io.eranalytics.pipeline.collection.route;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class RouteMapperTest {
    private final RouteMapper mapper = new RouteMapper(new ObjectMapper());

    @Test
    void parsesEncodedArraysVersionEpochAndPreservesPathOrder() {
        RouteSnapshot result = mapper.map(new RouteKey(41, 7039), RouteFixture.response(7039));
        assertThat(result.paths()).containsExactly(170, 130);
        assertThat(result.weaponCodes()).containsExactly(100001, 200001);
        assertThat(result.remoteTransferItemCodes()).isEmpty();
        assertThat(result.version()).containsExactly(12, 5, 0);
        assertThat(result.v2SeasonId()).isZero();
        assertThat(result.updateDtm().toEpochMilli()).isEqualTo(1720000000000L);
    }

    @Test
    void keepsOtherFieldsWithUnknownOrOverflowVersion() {
        for (String version : new String[] {"preview", "99999999999999.5.0"}) {
            JsonNode response = RouteFixture.response(7039);
            ((ObjectNode) response.path("result").path("recommendWeaponRoute"))
                    .put("version", version);
            assertThat(mapper.map(new RouteKey(41, 7039), response).version()).isEmpty();
        }
    }

    @Test
    void rejectsMismatchedIdAndMalformedArrayInsteadOfPartialPersistence() {
        assertThatThrownBy(() -> mapper.map(new RouteKey(41, 1), RouteFixture.response(2)))
                .isInstanceOf(IllegalArgumentException.class);
        JsonNode response = RouteFixture.response(1);
        ((ObjectNode) response.path("result").path("recommendWeaponRoute"))
                .put("paths", "[170,1.5]");
        assertThatThrownBy(() -> mapper.map(new RouteKey(41, 1), response))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
