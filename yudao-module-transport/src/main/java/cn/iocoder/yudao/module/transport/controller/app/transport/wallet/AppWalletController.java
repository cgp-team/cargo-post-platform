package cn.iocoder.yudao.module.transport.controller.app.transport.wallet;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.WalletAccountDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.WalletTransactionDO;
import cn.iocoder.yudao.module.transport.service.wallet.PayService;
import cn.iocoder.yudao.module.transport.service.wallet.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "用户 App - 钱包")
@RestController
@RequestMapping("/transport/wallet")
@Validated
public class AppWalletController {

    @Resource
    private WalletService walletService;
    @Resource
    private PayService payService;

    @GetMapping("/balance")
    @Operation(summary = "查询余额")
    public CommonResult<Map<String, Object>> balance() {
        Long userId = getLoginUserId();
        WalletAccountDO account = walletService.getOrCreateAccount(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("balance", account.getBalance());
        result.put("frozenAmount", account.getFrozenAmount());
        result.put("totalRecharge", account.getTotalRecharge());
        result.put("totalSpent", account.getTotalSpent());
        return success(result);
    }

    @PostMapping("/create-pay-order")
    @Operation(summary = "创建充值支付订单（返回调起参数）")
    public CommonResult<Map<String, Object>> createPayOrder(
            @RequestParam("amount") BigDecimal amount,
            @RequestParam(value = "channel", defaultValue = "alipay") String channel,
            @RequestParam(value = "requestNo", required = false) String requestNo) {
        Long userId = getLoginUserId();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return CommonResult.error(400, "充值金额必须大于0");
        }
        // 幂等键要和库里 request_no 列宽对得上，超长的键会在 insert 时才炸，那已经不是"参数不对"而是"500"了。
        if (requestNo != null && requestNo.trim().length() > 64) {
            return CommonResult.error(400, "requestNo 最长 64 个字符");
        }
        try {
            Map<String, String> payParams = payService.createPayOrder(userId, amount, channel, requestNo);
            Map<String, Object> result = new LinkedHashMap<>(payParams);
            return success(result);
        } catch (IllegalArgumentException e) {
            return CommonResult.error(400, e.getMessage());
        }
    }

    @GetMapping("/query-pay-order")
    @Operation(summary = "查询支付订单状态（前端轮询）")
    public CommonResult<Map<String, Object>> queryPayOrder(@RequestParam("orderNo") String orderNo) {
        // 只认当前登录用户名下的单：这一支会触发放款，不能对全网开放触发权。
        return success(payService.queryAlipayOrder(orderNo, getLoginUserId()));
    }

    @PostMapping("/recharge")
    @Operation(summary = "直接充值（模拟模式，支付未配置时使用）")
    public CommonResult<Map<String, Object>> recharge(@RequestParam("amount") BigDecimal amount) {
        Long userId = getLoginUserId();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return CommonResult.error(400, "充值金额必须大于0");
        }
        WalletTransactionDO tx = walletService.recharge(userId, amount, "alipay", null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transactionNo", tx.getTransactionNo());
        result.put("amount", tx.getAmount());
        result.put("balanceAfter", tx.getBalanceAfter());
        result.put("status", "success");
        return success(result);
    }

    @GetMapping("/transactions")
    @Operation(summary = "交易记录")
    public CommonResult<Map<String, Object>> transactions(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        Long userId = getLoginUserId();
        List<WalletTransactionDO> list = walletService.getRecentTransactions(userId, limit);
        List<Map<String, Object>> items = list.stream().map(tx -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("transactionNo", tx.getTransactionNo());
            m.put("type", tx.getType());
            m.put("amount", tx.getAmount());
            m.put("balanceAfter", tx.getBalanceAfter());
            m.put("title", tx.getTitle());
            m.put("payMethod", tx.getPayMethod());
            m.put("status", tx.getStatus());
            m.put("createTime", tx.getCreateTime());
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", items.size());
        return success(result);
    }

    @PostMapping("/pay")
    @Operation(summary = "余额支付")
    public CommonResult<Map<String, Object>> pay(
            @RequestParam("amount") BigDecimal amount,
            @RequestParam(value = "title", defaultValue = "消费") String title,
            @RequestParam(value = "refNo", required = false) String refNo) {
        Long userId = getLoginUserId();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return CommonResult.error(400, "支付金额必须大于0");
        }
        try {
            WalletTransactionDO tx = walletService.consume(userId, amount, title, refNo);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("transactionNo", tx.getTransactionNo());
            result.put("amount", tx.getAmount());
            result.put("balanceAfter", tx.getBalanceAfter());
            result.put("status", "success");
            return success(result);
        } catch (IllegalArgumentException e) {
            return CommonResult.error(400, e.getMessage());
        }
    }
}
