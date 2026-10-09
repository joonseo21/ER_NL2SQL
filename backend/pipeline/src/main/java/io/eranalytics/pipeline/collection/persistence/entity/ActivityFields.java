package io.eranalytics.pipeline.collection.persistence.entity;

import io.eranalytics.pipeline.erapi.dto.ActivityDto;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivityFields {
    @Column(name = "add_telephoto_camera")
    private Integer addTelephotoCamera;

    @Column(name = "remove_telephoto_camera")
    private Integer removeTelephotoCamera;

    @Column(name = "use_hyper_loop")
    private Integer useHyperLoop;

    @Column(name = "use_security_console")
    private Integer useSecurityConsole;

    @Column(name = "use_recon_drone")
    private Integer useReconDrone;

    @Column(name = "use_emp_drone")
    private Integer useEmpDrone;

    @Column(name = "tactical_skill_use_count")
    private Integer tacticalSkillUseCount;

    @Column(name = "enter_dimension_rift")
    private Integer enterDimensionRift;

    @Column(name = "win_from_dimension_rift")
    private Integer winFromDimensionRift;

    @Column(name = "enter_dimension_empowered_rift")
    private Integer enterDimensionEmpoweredRift;

    @Column(name = "win_from_dimension_empowered_rift")
    private Integer winFromDimensionEmpoweredRift;

    @Column(name = "sum_get_buff_cube")
    private Integer sumGetBuffCube;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "item_transferred_drone", columnDefinition = "integer[]")
    private Integer[] itemTransferredDrone;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "item_transferred_console", columnDefinition = "integer[]")
    private Integer[] itemTransferredConsole;

    public ActivityFields(ActivityDto data) {
        this.addTelephotoCamera = data.addTelephotoCamera();
        this.removeTelephotoCamera = data.removeTelephotoCamera();
        this.useHyperLoop = data.useHyperLoop();
        this.useSecurityConsole = data.useSecurityConsole();
        this.useReconDrone = data.useReconDrone();
        this.useEmpDrone = data.useEmpDrone();
        this.tacticalSkillUseCount = data.tacticalSkillUseCount();
        this.enterDimensionRift = data.enterDimensionRift();
        this.winFromDimensionRift = data.winFromDimensionRift();
        this.enterDimensionEmpoweredRift = data.enterDimensionEmpoweredRift();
        this.winFromDimensionEmpoweredRift = data.winFromDimensionEmpoweredRift();
        this.sumGetBuffCube = data.sumGetBuffCube();
        this.itemTransferredDrone = data.itemTransferredDrone() == null ? null : data.itemTransferredDrone().toArray(Integer[]::new);
        this.itemTransferredConsole = data.itemTransferredConsole() == null ? null : data.itemTransferredConsole().toArray(Integer[]::new);
    }
}
