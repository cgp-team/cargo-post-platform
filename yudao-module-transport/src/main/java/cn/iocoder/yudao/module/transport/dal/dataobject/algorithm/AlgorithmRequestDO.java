package cn.iocoder.yudao.module.transport.dal.dataobject.algorithm;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 算法请求留痕：requestId 幂等键、快照哈希、原始请求与响应摘要（适配层责任第 1、7 条）。
 */
@TableName("transport_algorithm_request")
@KeySequence("transport_algorithm_request_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmRequestDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** 全局唯一请求标识，算法侧幂等键 */
    private String requestId;

    /** 请求快照（不含 requestId）的 SHA-256 */
    private String snapshotHash;

    /** 原始请求 JSON */
    private String requestJson;

    /** 校验通过的响应 JSON；调用失败为空 */
    private String responseJson;

    /** 状态，参见 AlgorithmRequestStatusEnum */
    private Integer status;

    /** 失败时的业务错误码 */
    private String errorCode;

    /** 失败时的错误信息 */
    private String errorMessage;

    /** 算法版本，随镜像管理 */
    private String algorithmVersion;

    /** 默认参数版本 */
    private String parameterVersion;

}
