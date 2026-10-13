package cn.iocoder.yudao.module.transport.dal.mysql.wallet;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.WalletAccountDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WalletAccountMapper extends BaseMapperX<WalletAccountDO> {
    default WalletAccountDO selectByUserId(Long userId) {
        return selectOne(WalletAccountDO::getUserId, userId);
    }
}
