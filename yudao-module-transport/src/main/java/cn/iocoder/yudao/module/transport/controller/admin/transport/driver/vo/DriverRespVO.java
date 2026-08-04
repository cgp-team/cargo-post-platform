package cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description="Driver Response")
@Data @EqualsAndHashCode(callSuper=true) @ToString(callSuper=true)
public class DriverRespVO extends DriverBaseVO {
    @Schema(description="ID")
    private Long id;
    @Schema(description="driver name")
    private String name;

    @Schema(description="mobile")
    private String mobile;

    @Schema(description="license number")
    private String licenseNo;

    @Schema(description="license expire date")
    private LocalDate licenseExpireDate;
    @Schema(description="Create time")
    private LocalDateTime createTime;
}
