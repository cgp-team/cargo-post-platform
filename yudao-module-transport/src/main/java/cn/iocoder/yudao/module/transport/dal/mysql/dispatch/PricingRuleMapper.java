package cn.iocoder.yudao.module.transport.dal.mysql.dispatch;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.PricingRuleDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PricingRuleMapper extends BaseMapperX<PricingRuleDO> {

    /** 取单行配置（按 id 升序首条；多行时最早一行生效） */
    default PricingRuleDO selectFirst() {
        return selectOne(new LambdaQueryWrapperX<PricingRuleDO>()
                .orderByAsc(PricingRuleDO::getId).last("LIMIT 1"));
    }
}
