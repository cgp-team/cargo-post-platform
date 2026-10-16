package cn.iocoder.yudao.module.aiassist.service.capacityconflict;

import cn.iocoder.yudao.module.aiassist.controller.admin.capacityconflict.vo.CapacityConflictCheckReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.capacityconflict.vo.CapacityConflictCheckRespVO;

/**
 * 客货空间冲突检视 Service（契约 §6 第 3 项）。
 *
 * <p>确定性约束校验：核定载客/载货 vs 预报量，输出冲突点与缓冲建议。只要有输入就给结论，
 * 不依赖模型、不写任何业务数据。
 */
public interface CapacityConflictService {

    /**
     * 执行一次空间冲突检视。
     *
     * @param reqVO 检视输入（车辆核定容量 + 分段预报量）
     * @return 冲突点与缓冲建议
     */
    CapacityConflictCheckRespVO check(CapacityConflictCheckReqVO reqVO);

}
