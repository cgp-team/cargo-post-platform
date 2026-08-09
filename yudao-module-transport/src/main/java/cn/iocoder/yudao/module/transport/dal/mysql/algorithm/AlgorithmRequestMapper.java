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

}
