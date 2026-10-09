package io.eranalytics.pipeline.erapi.dto;

import java.util.List;

public record ActivityDto(
        Integer addTelephotoCamera,
        Integer removeTelephotoCamera,
        Integer useHyperLoop,
        Integer useSecurityConsole,
        Integer useReconDrone,
        Integer useEmpDrone,
        Integer tacticalSkillUseCount,
        Integer enterDimensionRift,
        Integer winFromDimensionRift,
        Integer enterDimensionEmpoweredRift,
        Integer winFromDimensionEmpoweredRift,
        Integer sumGetBuffCube,
        List<Integer> itemTransferredDrone,
        List<Integer> itemTransferredConsole
) {
}
