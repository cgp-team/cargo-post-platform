package cn.iocoder.yudao.module.transport.controller.app.transport.voice.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Schema(description = "用户 App - 语音翻译 Request VO")
@Data
public class AppVoiceTranslateReqVO {

    @Schema(description = "原文", requiredMode = Schema.RequiredMode.REQUIRED, example = "你好")
    @NotEmpty(message = "翻译文本不能为空")
    private String text;

    @Schema(description = "源语言 zh/en，默认 zh", example = "zh")
    private String from;

    @Schema(description = "目标语言 en/zh，默认 en", example = "en")
    private String to;

}
