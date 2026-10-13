package cn.iocoder.yudao.module.transport.service.wallet;

import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.PayOrderDO;
import cn.iocoder.yudao.module.transport.dal.mysql.wallet.PayOrderMapper;
import cn.iocoder.yudao.module.transport.framework.pay.PayProperties;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeWapPayModel;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 支付服务：创建支付订单 → 生成调起参数 → 处理回调 → 加余额。
 *
 * 充值流程：
 * 1. App 调 POST /wallet/create-pay-order → 本服务创建 pay_order(status=0) + 返回签名串
 * 2. App 拿签名串调起支付宝/微信 → 用户完成支付
 * 3. 支付宝/微信 POST 回调 → /wallet/pay/notify → 验签 → 更新 pay_order(status=1) → 加余额
 */
@Slf4j
@Service
public class PayService {

    @Resource
    private PayProperties payProps;
    @Resource
    private PayOrderMapper payOrderMapper;
    @Resource
    private WalletService walletService;

    private AlipayClient alipayClient;

    @PostConstruct
    public void init() {
        if (payProps.isAlipayEnabled()) {
            alipayClient = new DefaultAlipayClient(
                    payProps.getAlipayGateway(),
                    payProps.getAlipayAppId(),
                    payProps.getAlipayPrivateKey(),
                    "json", "UTF-8",
                    payProps.getAlipayPublicKey(),
                    "RSA2"
            );
            log.info("[PayService] 支付宝支付已启用 appId={}", payProps.getAlipayAppId());
        } else {
            log.warn("[PayService] 支付宝未配置，充值将走模拟模式");
        }
    }

    /**
     * 创建充值支付订单。
     * @return 支付宝：返回 orderString（客户端直接传给 SDK）；微信：返回 prepay 参数 Map。
     *         未配置支付渠道时返回 null，调用方走模拟充值。
     */
    public Map<String, String> createPayOrder(Long userId, BigDecimal amount, String channel) {
        String orderNo = "P" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));

        PayOrderDO order = PayOrderDO.builder()
                .orderNo(orderNo)
                .userId(userId)
                .channel(channel)
                .amount(amount)
                .status(0)
                .bizType("recharge")
                .build();
        payOrderMapper.insert(order);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("orderNo", orderNo);

        if ("alipay".equals(channel) && payProps.isAlipayEnabled()) {
            String orderString = createAlipayOrder(orderNo, amount, "钱包充值");
            result.put("orderString", orderString);
            result.put("channel", "alipay");
        } else if ("wechat".equals(channel) && payProps.isWechatEnabled()) {
            // TODO: 微信支付需要商户证书，配置完成后实现
            result.put("channel", "wechat");
            result.put("message", "微信支付待配置商户证书");
        } else {
            result.put("channel", channel);
            result.put("simulated", "true");
        }
        return result;
    }

    private String createAlipayOrder(String orderNo, BigDecimal amount, String subject) {
        try {
            AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
            request.setNotifyUrl(payProps.getAlipayNotifyUrl());
            AlipayTradeWapPayModel model = new AlipayTradeWapPayModel();
            model.setOutTradeNo(orderNo);
            model.setTotalAmount(amount.toPlainString());
            model.setSubject(subject);
            model.setProductCode("QUICK_WAP_WAY");
            request.setBizModel(model);
            // sdkExecute 返回的 response body 就是客户端调起支付的 orderString
            return alipayClient.sdkExecute(request).getBody();
        } catch (Exception e) {
            log.error("[createAlipayOrder] 生成签名失败 orderNo={}", orderNo, e);
            return "";
        }
    }

    /**
     * 支付宝异步回调处理。
     * 验签 → 查订单 → 幂等加余额 → 返回 "success"。
     */
    @Transactional(rollbackFor = Exception.class)
    public String handleAlipayNotify(Map<String, String> params) {
        // 1. 验签
        boolean signVerified;
        try {
            signVerified = com.alipay.api.internal.util.AlipaySignature.rsaCheckV1(
                    params,
                    payProps.getAlipayPublicKey(),
                    "UTF-8",
                    "RSA2"
            );
        } catch (Exception e) {
            log.error("[handleAlipayNotify] 验签异常", e);
            return "failure";
        }
        if (!signVerified) {
            log.warn("[handleAlipayNotify] 验签失败");
            return "failure";
        }

        // 2. 解析参数
        String orderNo = params.get("out_trade_no");
        String tradeNo = params.get("trade_no");
        String tradeStatus = params.get("trade_status");

        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            return "success"; // 非成功状态，不处理
        }

        // 3. 查订单
        PayOrderDO order = payOrderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            log.warn("[handleAlipayNotify] 订单不存在 orderNo={}", orderNo);
            return "failure";
        }
        // 幂等：已支付则直接返回成功
        if (order.getStatus() == 1) {
            return "success";
        }

        // 4. 更新订单 + 加余额
        order.setStatus(1);
        order.setChannelTradeNo(tradeNo);
        order.setNotifyBody(params.toString());
        payOrderMapper.updateById(order);

        walletService.recharge(order.getUserId(), order.getAmount(), "alipay", orderNo);

        log.info("[handleAlipayNotify] 充值成功 orderNo={} amount={} tradeNo={}",
                orderNo, order.getAmount(), tradeNo);
        return "success";
    }

    /**
     * 主动查询支付宝订单状态（用于前端轮询确认）。
     */
    public Map<String, Object> queryAlipayOrder(String orderNo) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", orderNo);

        PayOrderDO order = payOrderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            result.put("status", "not_found");
            return result;
        }
        result.put("status", order.getStatus() == 1 ? "paid" : order.getStatus() == 0 ? "pending" : "closed");
        result.put("amount", order.getAmount());

        if (order.getStatus() == 0 && payProps.isAlipayEnabled()) {
            try {
                AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
                request.setBizContent("{\"out_trade_no\":\"" + orderNo + "\"}");
                AlipayTradeQueryResponse response = alipayClient.execute(request);
                if (response.isSuccess() && "TRADE_SUCCESS".equals(response.getTradeStatus())) {
                    order.setStatus(1);
                    order.setChannelTradeNo(response.getTradeNo());
                    payOrderMapper.updateById(order);
                    walletService.recharge(order.getUserId(), order.getAmount(), "alipay", orderNo);
                    result.put("status", "paid");
                }
            } catch (Exception e) {
                log.warn("[queryAlipayOrder] 查询失败 orderNo={}", orderNo, e);
            }
        }
        return result;
    }
}
