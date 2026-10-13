package cn.iocoder.yudao.module.transport.dal.mysql.wallet;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.WalletTransactionDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WalletTransactionMapper extends BaseMapperX<WalletTransactionDO> {

    default PageResult<WalletTransactionDO> selectPage(Long userId, PageParam pageParam) {
        return selectPage(pageParam, new LambdaQueryWrapperX<WalletTransactionDO>()
                .eqIfPresent(WalletTransactionDO::getUserId, userId)
                .orderByDesc(WalletTransactionDO::getId));
    }

    default java.util.List<WalletTransactionDO> selectRecentByUserId(Long userId, int limit) {
        return selectList(new LambdaQueryWrapperX<WalletTransactionDO>()
                .eq(WalletTransactionDO::getUserId, userId)
                .eq(WalletTransactionDO::getStatus, 1)
                .orderByDesc(WalletTransactionDO::getId)
                .last("LIMIT " + limit));
    }
}
