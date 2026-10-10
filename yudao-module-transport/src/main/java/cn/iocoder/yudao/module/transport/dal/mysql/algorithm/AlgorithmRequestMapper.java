package cn.iocoder.yudao.module.transport.dal.mysql.algorithm;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.algorithm.AlgorithmRequestDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AlgorithmRequestMapper extends BaseMapperX<AlgorithmRequestDO> {

    /**
     * 查询指定时间以来、相同快照哈希的最近一次请求，用于 24 小时幂等窗口内的结果复用。
     */
    default AlgorithmRequestDO selectRecentBySnapshotHash(String snapshotHash, LocalDateTime since) {
        List<AlgorithmRequestDO> list = selectList(new LambdaQueryWrapperX<AlgorithmRequestDO>()
                .eq(AlgorithmRequestDO::getSnapshotHash, snapshotHash)
                .ge(AlgorithmRequestDO::getCreateTime, since)
                .orderByDesc(AlgorithmRequestDO::getId)
                .last("LIMIT 1"));
        return CollUtil.getFirst(list);
    }

    /**
     * 相同快照哈希的最近一次请求（不限时间）。
     * 用于唯一键（uk_algorithm_request_snapshot）兜底：并发插入撞键、或窗口外历史记录占键时，
     * 由调用方重查后原地复用该行。
     */
    default AlgorithmRequestDO selectLatestBySnapshotHash(String snapshotHash) {
        List<AlgorithmRequestDO> list = selectList(new LambdaQueryWrapperX<AlgorithmRequestDO>()
                .eq(AlgorithmRequestDO::getSnapshotHash, snapshotHash)
                .orderByDesc(AlgorithmRequestDO::getId)
                .last("LIMIT 1"));
        return CollUtil.getFirst(list);
    }

}
