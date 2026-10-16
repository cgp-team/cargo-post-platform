package cn.iocoder.yudao.module.aiassist.controller.admin.task;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.aiassist.core.AiTasks;
import cn.iocoder.yudao.module.aiassist.controller.admin.task.vo.AiTaskReqVO;
import cn.iocoder.yudao.module.aiassist.service.task.AiTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * AI 辅助任务出口（契约 §7 的五个需要模型的活儿 + §6 第 2 项那条规则版情景提示）。
 *
 * <p>结构约束（§0）在这一层的落法：
 * <ul>
 *   <li>返回体<b>只有草稿</b>（{@link AiTasks.Draft}）：这里没有任何一个字段会被写进业务表，
 *       也没有任何"我已经替你改好了"的措辞；采纳由值班员在既有页面完成；</li>
 *   <li>这些口<b>不钉幂等键</b>：重复执行只会多一条 {@code ai_invocation_log}（审计记录，本来就该一条一次），
 *       不会多出一份业务数据 —— 与幂等闸的口径一致（在册表只收"重复一次多一条真记录"的口）；</li>
 *   <li>情景提示那一格走<b>另一个权限位</b>：它不需要模型，AI 关掉时也必须能用（否则"关掉 AI 仍完整可用"就成了空话）。</li>
 * </ul>
 */
@Tag(name = "管理后台 - AI 辅助任务（只出参考意见）")
@RestController
@RequestMapping("/transport/ai/task")
@Validated
public class AiTaskController {

    @Resource
    private AiTaskService aiTaskService;

    @PostMapping("/classify-report")
    @Operation(summary = "上报语义分类建议（草稿，不改单）")
    @PreAuthorize("@ss.hasPermission('transport:ai:task:run')")
    public CommonResult<AiTasks.Draft> classifyReport(@Valid @RequestBody AiTaskReqVO reqVO) {
        return success(aiTaskService.classifyReport(reqVO));
    }

    @PostMapping("/photo-support")
    @Operation(summary = "现场照片是否支撑描述（需视觉模型；未配置时报未就绪）")
    @PreAuthorize("@ss.hasPermission('transport:ai:task:run')")
    public CommonResult<AiTasks.Draft> photoSupport(@Valid @RequestBody AiTaskReqVO reqVO) {
        return success(aiTaskService.checkPhotoSupport(reqVO));
    }

    @PostMapping("/merge-reports")
    @Operation(summary = "口语上报同义归并（草稿，合并动作由人工在既有页面做）")
    @PreAuthorize("@ss.hasPermission('transport:ai:task:run')")
    public CommonResult<AiTasks.Draft> mergeReports(@Valid @RequestBody AiTaskReqVO reqVO) {
        return success(aiTaskService.mergeReports(reqVO));
    }

    @PostMapping("/cluster-feedback")
    @Operation(summary = "用户反馈主题聚类（供人工排期判断，不给用户/司机定性）")
    @PreAuthorize("@ss.hasPermission('transport:ai:task:run')")
    public CommonResult<AiTasks.Draft> clusterFeedback(@Valid @RequestBody AiTaskReqVO reqVO) {
        return success(aiTaskService.clusterFeedback(reqVO));
    }

    @PostMapping("/polish-briefing")
    @Operation(summary = "简报成稿润色（数字不许改，改了判不合格）")
    @PreAuthorize("@ss.hasPermission('transport:ai:task:run')")
    public CommonResult<AiTasks.Draft> polishBriefing(@Valid @RequestBody AiTaskReqVO reqVO) {
        return success(aiTaskService.polishBriefing(reqVO));
    }

    @GetMapping("/scenario-hints")
    @Operation(summary = "某天生效的乡镇事件 → 提前留舱提示（规则版，不需要模型）")
    @PreAuthorize("@ss.hasPermission('transport:ai:scenario:query')")
    public CommonResult<AiTasks.Draft> scenarioHints(
            @RequestParam("day") @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate day) {
        return success(aiTaskService.scenarioHints(day));
    }
}
