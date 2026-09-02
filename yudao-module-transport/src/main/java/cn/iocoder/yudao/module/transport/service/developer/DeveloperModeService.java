package cn.iocoder.yudao.module.transport.service.developer;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 开发者模式服务：基于 Redis 的 per-user 开发者模式开关。
 *
 * Redis Key: transport:developer:{userId}
 * Value: "true" / "false"
 * TTL: 不过期（手动关闭）
 *
 * 注意：developerMode 只是状态，不是权限。RBAC 权限由 system_menu 体系独立控制。
 */
@Service
public class DeveloperModeService {

    private static final String KEY_PREFIX = "transport:developer:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询当前用户是否开启开发者模式
     */
    public boolean isDeveloperMode(Long userId) {
        if (userId == null) {
            return false;
        }
        String value = stringRedisTemplate.opsForValue().get(formatKey(userId));
        return "true".equals(value);
    }

    /**
     * 开启开发者模式
     */
    public void enableDeveloperMode(Long userId) {
        if (userId == null) {
            return;
        }
        stringRedisTemplate.opsForValue().set(formatKey(userId), "true");
    }

    /**
     * 关闭开发者模式
     */
    public void disableDeveloperMode(Long userId) {
        if (userId == null) {
            return;
        }
        stringRedisTemplate.delete(formatKey(userId));
    }

    private static String formatKey(Long userId) {
        return KEY_PREFIX + userId;
    }

}
