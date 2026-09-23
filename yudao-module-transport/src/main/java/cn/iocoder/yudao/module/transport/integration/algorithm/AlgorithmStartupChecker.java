package cn.iocoder.yudao.module.transport.integration.algorithm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自检：算法服务连通性与 base-url 告警（P0：避免静默打空/误连 mock）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlgorithmStartupChecker implements ApplicationRunner {

    private final AlgorithmAdapter algorithmAdapter;
    private final AlgorithmProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        boolean ok = algorithmAdapter.healthCheck();
        if (ok) {
            log.info("[AlgorithmStartupChecker] 算法服务可达 baseUrl={}", properties.getBaseUrl());
        } else {
            log.warn("[AlgorithmStartupChecker] 算法服务不可达 baseUrl={} — 智能派单/动态调度将失败或降级；请检查 ALGORITHM_BASE_URL",
                    properties.getBaseUrl());
        }
    }
}
