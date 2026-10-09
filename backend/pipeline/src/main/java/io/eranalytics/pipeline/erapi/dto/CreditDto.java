package io.eranalytics.pipeline.erapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CreditDto(
        Integer creditRevivalCount,
        Integer creditRevivedOthersCount,
        @JsonProperty("totalGainVFCredit") Integer totalGainVfCredit,
        @JsonProperty("totalUseVFCredit") Integer totalUseVfCredit,
        @JsonProperty("killPlayerGainVFCredit") Integer killPlayerGainVfCredit,
        @JsonProperty("killChickenGainVFCredit") Integer killChickenGainVfCredit,
        @JsonProperty("killBoarGainVFCredit") Integer killBoarGainVfCredit,
        @JsonProperty("killWildDogGainVFCredit") Integer killWildDogGainVfCredit,
        @JsonProperty("killWolfGainVFCredit") Integer killWolfGainVfCredit,
        @JsonProperty("killBearGainVFCredit") Integer killBearGainVfCredit,
        @JsonProperty("killBatGainVFCredit") Integer killBatGainVfCredit,
        @JsonProperty("killRavenGainVFCredit") Integer killRavenGainVfCredit,
        @JsonProperty("killWicklineGainVFCredit") Integer killWicklineGainVfCredit,
        @JsonProperty("killAlphaGainVFCredit") Integer killAlphaGainVfCredit,
        @JsonProperty("killOmegaGainVFCredit") Integer killOmegaGainVfCredit,
        @JsonProperty("killGammaGainVFCredit") Integer killGammaGainVfCredit,
        @JsonProperty("killDroneGainVFCredit") Integer killDroneGainVfCredit,
        @JsonProperty("killItemBountyGainVFCredit") Integer killItemBountyGainVfCredit,
        @JsonProperty("totalVFCredits") List<Integer> totalVfCredits,
        @JsonProperty("usedVFCredits") List<Integer> usedVfCredits,
        @JsonProperty("totalTKPerMin") List<Integer> totalTkPerMin
) {
}
