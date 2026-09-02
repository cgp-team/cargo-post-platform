package cn.iocoder.yudao.module.transport.service.developer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DeveloperModeService 单元测试
 */
class DeveloperModeServiceTest {

    private DeveloperModeService developerModeService;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        developerModeService = new DeveloperModeService();
        // 注入 mock 的 StringRedisTemplate
        try {
            var field = DeveloperModeService.class.getDeclaredField("stringRedisTemplate");
            field.setAccessible(true);
            field.set(developerModeService, stringRedisTemplate);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void isDeveloperMode_returns_true_when_redis_has_true() {
        when(valueOperations.get("transport:developer:100")).thenReturn("true");
        assertTrue(developerModeService.isDeveloperMode(100L));
    }

    @Test
    void isDeveloperMode_returns_false_when_redis_has_false() {
        when(valueOperations.get("transport:developer:100")).thenReturn("false");
        assertFalse(developerModeService.isDeveloperMode(100L));
    }

    @Test
    void isDeveloperMode_returns_false_when_redis_has_null() {
        when(valueOperations.get("transport:developer:100")).thenReturn(null);
        assertFalse(developerModeService.isDeveloperMode(100L));
    }

    @Test
    void isDeveloperMode_returns_false_when_userId_is_null() {
        assertFalse(developerModeService.isDeveloperMode(null));
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void enableDeveloperMode_sets_true_in_redis() {
        developerModeService.enableDeveloperMode(100L);
        verify(valueOperations).set("transport:developer:100", "true");
    }

    @Test
    void enableDeveloperMode_does_nothing_when_userId_is_null() {
        developerModeService.enableDeveloperMode(null);
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void disableDeveloperMode_deletes_key_from_redis() {
        developerModeService.disableDeveloperMode(100L);
        verify(stringRedisTemplate).delete("transport:developer:100");
    }

    @Test
    void disableDeveloperMode_does_nothing_when_userId_is_null() {
        developerModeService.disableDeveloperMode(null);
        verifyNoInteractions(stringRedisTemplate);
    }

}
