package cn.iocoder.yudao.module.transport.controller.admin.transport.wallet;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.WalletAccountDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.WalletTransactionDO;
import cn.iocoder.yudao.module.transport.service.wallet.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 钱包管理")
@RestController
@RequestMapping("/transport/wallet")
@Validated
public class WalletController {

    @Resource
    private WalletService walletService;

    @GetMapping("/account/get")
    @Operation(summary = "查询用户钱包")
    @Parameter(name = "userId", description = "用户ID", required = true)
    @PreAuthorize("@ss.hasPermission('transport:wallet:query')")
    public CommonResult<Map<String, Object>> getAccount(@RequestParam("userId") Long userId) {
        WalletAccountDO account = walletService.getOrCreateAccount(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", account.getId());
        result.put("userId", account.getUserId());
        result.put("balance", account.getBalance());
        result.put("frozenAmount", account.getFrozenAmount());
        result.put("totalRecharge", account.getTotalRecharge());
        result.put("totalSpent", account.getTotalSpent());
        result.put("status", account.getStatus());
        return success(result);
    }

    @GetMapping("/transaction/page")
    @Operation(summary = "交易记录分页")
    @PreAuthorize("@ss.hasPermission('transport:wallet:query')")
    public CommonResult<PageResult<WalletTransactionDO>> transactionPage(
            @RequestParam(value = "userId", required = false) Long userId,
            @Validated PageParam pageParam) {
        return success(walletService.getTransactionPage(userId, pageParam));
    }

    @PostMapping("/adjust-balance")
    @Operation(summary = "手动调整余额（运营操作）")
    @PreAuthorize("@ss.hasPermission('transport:wallet:adjust')")
    public CommonResult<String> adjustBalance(
            @RequestParam("userId") Long userId,
            @RequestParam("amount") BigDecimal amount,
            @RequestParam(value = "reason", defaultValue = "运营调整") String reason) {
        if (amount.compareTo(BigDecimal.ZERO) > 0) {
            walletService.recharge(userId, amount, "balance", "admin-adjust");
        } else if (amount.compareTo(BigDecimal.ZERO) < 0) {
            walletService.consume(userId, amount.negate(), reason, "admin-adjust");
        }
        return success("调整成功");
    }
}
