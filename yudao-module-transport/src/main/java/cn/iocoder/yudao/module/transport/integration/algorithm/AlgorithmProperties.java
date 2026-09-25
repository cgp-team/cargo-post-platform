package cn.iocoder.yudao.module.transport.integration.algorithm;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 路线规划算法服务接入配置。
 * 仅 transport 算法适配层可访问算法服务；前端与算法容器之间无公开路由。
 */
@ConfigurationProperties(prefix = "yudao.transport.algorithm")
@Validated
@Data
public class AlgorithmProperties {

    /** 算法服务基础地址，如 http://127.0.0.1:18081（自研 algorithm 容器宿主端口） */
    @NotBlank(message = "算法服务地址不能为空")
    private String baseUrl;

    /** 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 读取超时；算法同步计算最长 10 秒，留有余量 */
    private Duration readTimeout = Duration.ofSeconds(8);

    /** 408 轮询间隔，契约建议不低于 2 秒 */
    private Duration pollInterval = Duration.ofSeconds(2);

    /** 最大轮询次数（与 pollBudget 双重上限，先到为准） */
    private Integer maxPollAttempts = 10;

    /** 轮询总预算：408 后的轮询整体不超过该时长，避免单请求长时间独占 Tomcat 线程 */
    private Duration pollBudget = Duration.ofSeconds(15);

    /** 可重试错误（502/503/504 与网络错误）的最大重试次数，不含首次请求 */
    private Integer maxRetries = 1;

    /** 重试退避间隔 */
    private Duration retryBackoff = Duration.ofMillis(500);

    /** 适配层幂等窗口：相同快照在该时间内复用同一业务任务，与算法侧 requestId 保留期一致 */
    private Duration idempotencyWindow = Duration.ofHours(24);

}
