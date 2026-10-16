package cn.iocoder.yudao.module.aiassist.service.invocation;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.aiassist.controller.admin.invocation.vo.AiInvocationLogPageReqVO;
import cn.iocoder.yudao.module.aiassist.dal.dataobject.invocation.AiInvocationLogDO;
import cn.iocoder.yudao.module.aiassist.dal.mysql.invocation.AiInvocationLogMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

/**
 * AI 调用日志实现（契约 §5）。
 */
@Service
@Validated
@Slf4j
public class AiInvocationLogServiceImpl implements AiInvocationLogService {

    @Resource
    private AiInvocationLogMapper invocationLogMapper;

    @Override
    public boolean record(AiInvocationLogDO logDO) {
        try {
            invocationLogMapper.insert(logDO);
            return true;
        } catch (Exception ex) {
            // 旁路：留痕失败不得阻断主流程；但绝不静默 —— 至少留一条 error 级痕迹。
            log.error("[record][AI 调用留痕失败，已旁路] scene={} operatorId={}",
                    logDO != null ? logDO.getScene() : null,
                    logDO != null ? logDO.getOperatorId() : null, ex);
            return false;
        }
    }

    @Override
    public PageResult<AiInvocationLogDO> getPage(AiInvocationLogPageReqVO reqVO) {
        return invocationLogMapper.selectPage(reqVO);
    }

}
