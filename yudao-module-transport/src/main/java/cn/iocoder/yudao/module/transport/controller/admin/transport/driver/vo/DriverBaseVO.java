package cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDate;

@Schema(description="Driver Base VO")
@Data
public class DriverBaseVO {
    @Schema(description="driver name")
    private String name;

    @Schema(description="mobile")
    private String mobile;

    @Schema(description="license number")
    private String licenseNo;

    @Schema(description="license expire date")
    private LocalDate licenseExpireDate;
}
