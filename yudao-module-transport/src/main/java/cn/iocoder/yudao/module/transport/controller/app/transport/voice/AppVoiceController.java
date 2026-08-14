package cn.iocoder.yudao.module.transport.controller.app.transport.voice;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.transport.controller.app.transport.voice.vo.AppVoiceTranslateReqVO;
import cn.iocoder.yudao.module.transport.service.transport.voice.AppVoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "用户 App - 语音服务（寄货语音输入 + 面对面翻译）")
@RestController
@RequestMapping("/transport/voice")
@Validated
public class AppVoiceController {

    @Resource
    private AppVoiceService voiceService;

    @PostMapping("/recognize")
    @Operation(summary = "语音识别：录音 → 文本（免登录工具）")
    @PermitAll
    public CommonResult<Map<String, Object>> recognize(@RequestPart("file") MultipartFile file,
                                                       @RequestParam(value = "lang", required = false, defaultValue = "zh") String lang) {
        String text = voiceService.recognize(readBytes(file), formatOf(file), lang);
        Map<String, Object> data = new HashMap<>();
        data.put("text", text);
        return success(data);
    }

    @PostMapping("/translate")
    @Operation(summary = "文本翻译：中英文互译（免登录工具）")
    @PermitAll
    public CommonResult<Map<String, Object>> translate(@Valid @RequestBody AppVoiceTranslateReqVO reqVO) {
        String translatedText = voiceService.translate(reqVO.getText(), reqVO.getFrom(), reqVO.getTo());
        Map<String, Object> data = new HashMap<>();
        data.put("translatedText", translatedText);
        return success(data);
    }

    // ========== 工具 ==========

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("读取音频失败", e);
        }
    }

    /** 根据文件名后缀推断格式：.wav→wav，.aac/.m4a→aac，其余默认 wav */
    private static String formatOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) {
            return "wav";
        }
        String lower = name.toLowerCase();
        if (lower.endsWith(".aac") || lower.endsWith(".m4a")) {
            return "aac";
        }
        return "wav";
    }

}
