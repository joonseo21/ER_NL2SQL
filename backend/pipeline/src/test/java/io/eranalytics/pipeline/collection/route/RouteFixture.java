package io.eranalytics.pipeline.collection.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class RouteFixture {
    private RouteFixture() {}

    public static JsonNode response(int id) {
        try {
            return new ObjectMapper()
                    .readTree(
                            """
                    {"code":200,"result":{"recommendWeaponRoute":{
                      "id":%d,"v2SeasonId":0,"characterCode":1,"weaponType":16,
                      "tacticalSkillGroupCode":120,"weaponCodes":"[100001,200001]",
                      "traitCodes":"[700001]","remoteTransferItemCodes":"[]",
                      "lateGameItemCodes":"{\\"1\\":[999999]}","paths":"[170,130]",
                      "v2WinRate":12.3,"v2Like":5,"v2AccumulateWinRate":11.2,
                      "v2AccumulateLike":9,"routeVersion":54,"updateDtm":1720000000000,
                      "version":"12.5.0","teamMode":3,"share":true,
                      "title":"discard","userNum":123,"userNickname":"discard"},
                      "recommendWeaponRouteDesc":{"desc":"discard","skillPath":"discard"}}}
                    """
                                    .formatted(id));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
