package cn.iocoder.yudao.module.transport.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * BE-13/14：把「通知发送 / 外部 HTTP / 循环写库」等慢操作移出事务——
 * 注册到当前事务的 afterCommit 阶段执行（此时连接已释放，事务时长不再随车辆/段数线性增长）。
 *
 * 无事务上下文（如单元测试直接 new ServiceImpl 调用）时立即执行，行为与同步版一致。
 */
@Slf4j
public final class TransactionAfterCommit {

    private TransactionAfterCommit() {
    }

    /**
     * 事务提交后执行 action；无事务同步时立即执行。
     * afterCommit 阶段的异常只记日志不外抛（主流程已提交，失败不应影响调用方）。
     */
    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (Exception ex) {
                        log.warn("[TransactionAfterCommit] 提交后处理失败（不影响主流程）: {}", ex.getMessage(), ex);
                    }
                }
            });
        } else {
            action.run();
        }
    }

}
