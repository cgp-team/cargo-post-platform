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
import org.springframework.dao.DuplicateKeyException;
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
     *
     * @param requestNo 客户端幂等键，可为空。同一 (userId, requestNo) 重复调用不新增订单行：
     *                  待支付的那笔重新生成调起参数，已支付的那笔回执 alreadyPaid。
     *                  没有这个键时，用户点两次充值 = 两笔待支付订单，回调只会加一次余额，
     *                  多出来的脏单要靠人工对账才说得清。
     * @return 支付宝：返回 orderString（客户端直接传给 SDK）；微信：返回 prepay 参数 Map。
     *         未配置支付渠道时返回 null，调用方走模拟充值。
     */
    public Map<String, String> createPayOrder(Long userId, BigDecimal amount, String channel, String requestNo) {
        String idemKey = (requestNo == null || requestNo.trim().isEmpty()) ? null : requestNo.trim();
        if (idemKey != null) {
            PayOrderDO existing = payOrderMapper.selectByRequestNo(userId, idemKey);
            if (existing != null) {
                return reuseOrder(existing, amount, channel);
            }
        }

        String orderNo = "P" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));

        PayOrderDO order = PayOrderDO.builder()
                .orderNo(orderNo)
                .requestNo(idemKey)
                .userId(userId)
                .channel(channel)
                .amount(amount)
                .status(0)
                .bizType("recharge")
                .build();
        try {
            payOrderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 「先查后插」有 TOCTOU 窗口，唯一键 uk_pay_order_user_request 兜底：
            // 并发里慢的那一边插不进去，重查一次走复用，而不是把库错误抛给用户（V027 同一手法）。
            PayOrderDO raced = idemKey == null ? null : payOrderMapper.selectByRequestNo(userId, idemKey);
            if (raced == null) {
                throw e;
            }
            return reuseOrder(raced, amount, channel);
        }
        return payParamsFor(order);
    }

    /** 幂等命中已有订单：金额或渠道对不上就是拿同一个键做了另一笔充值，拒绝掉而不是静默按旧单收。 */
    private Map<String, String> reuseOrder(PayOrderDO existing, BigDecimal amount, String channel) {
        if (existing.getAmount().compareTo(amount) != 0 || !channel.equals(existing.getChannel())) {
            throw new IllegalArgumentException("requestNo 已用于另一笔充值（金额或渠道不同），请换一个幂等键");
        }
        if (existing.getStatus() == 1) {
            Map<String, String> paid = new LinkedHashMap<>();
            paid.put("orderNo", existing.getOrderNo());
            paid.put("channel", existing.getChannel());
            paid.put("alreadyPaid", "true");
            return paid;
        }
        Map<String, String> result = payParamsFor(existing);
        result.put("reused", "true");
        return result;
    }

    /** 由订单行生成调起参数：新建与复用走同一段，两条路不会各自漂移。 */
    private Map<String, String> payParamsFor(PayOrderDO order) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("orderNo", order.getOrderNo());
        String channel = order.getChannel();

        if ("alipay".equals(channel) && payProps.isAlipayEnabled()) {
            String orderString = createAlipayOrder(order.getOrderNo(), order.getAmount(), "钱包充值");
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
     *
     * @param requesterUserId 当前登录用户。订单不是他的 ⇒ 返回 not_found 而不是「无权限」：
     *                        orderNo 是「P+时间戳+6 位随机数」这种可猜形状，能区分"不存在"和"是别人的"
     *                        就等于开了一台帮别人枚举订单号的判 oracle；
     *                        而且这一支在 status=0 时会去支付宝查证并加余额，
     *                        没有归属校验时，任何人拿别人的订单号轮询一次就可能替别人把钱充进别人的钱包
     *                        —— 加余额本身走的是 order.getUserId()，但触发权不该对全网开放。
     */
    public Map<String, Object> queryAlipayOrder(String orderNo, Long requesterUserId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderNo", orderNo);

        PayOrderDO order = payOrderMapper.selectByOrderNo(orderNo);
        if (order == null || !order.getUserId().equals(requesterUserId)) {
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
