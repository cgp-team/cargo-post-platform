package cn.iocoder.yudao.framework.security.core.filter;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ENG-02：鉴权拦截边界单元测试（无需数据库，纯 Mockito）。
 *
 * 覆盖安全边界：
 * 1. 空 token → 放行（匿名，由后续授权阶段决定 401/403）
 * 2. 伪造/不存在的 token（checkAccessToken 抛 401 ServiceException）→ 静默放行链路，不写认证上下文
 * 3. 过期 token（401）→ 同上
 * 4. 有效 admin token 访问 /admin-api → 认证成功，上下文含 userId
 * 5. admin token 访问 /app-api（userType 越权）→ AccessDeniedException，请求被终止（不进入业务链路）
 * 6. mock token 关闭时伪造 mock 前缀 token → 不产生认证
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenAuthenticationFilterTest {

    @Mock
    private OAuth2TokenCommonApi oauth2TokenApi;
    @Mock
    private GlobalExceptionHandler globalExceptionHandler;
    @Mock
    private FilterChain chain;

    private TokenAuthenticationFilter filter;
    private SecurityProperties securityProperties;

    private static final String VALID_TOKEN = "test-valid-token";

    @BeforeEach
    void setUp() {
        // SecurityContext 是 ThreadLocal：跨用例残留会导致断言互相污染
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        securityProperties = new SecurityProperties();
        securityProperties.setTokenHeader("Authorization");
        securityProperties.setMockEnable(false); // 关闭 mock 登录，避免测试环境绕过鉴权
        filter = new TokenAuthenticationFilter(securityProperties, globalExceptionHandler, oauth2TokenApi);
        // WebFrameworkUtils.getLoginUserType 依赖静态 properties（URL 前缀判定 admin/app）
        new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new cn.iocoder.yudao.framework.web.config.WebProperties());
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String uri, String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        req.setServletPath(uri); // WebFrameworkUtils 按 servletPath 前缀判定 admin/app 用户类型
        if (token != null) {
            req.addHeader("Authorization", "Bearer " + token);
        }
        return req;
    }

    private OAuth2AccessTokenCheckRespDTO tokenOf(Long userId, Integer userType) {
        return new OAuth2AccessTokenCheckRespDTO().setUserId(userId).setUserType(userType)
                .setTenantId(1L).setScopes(null).setExpiresTime(java.time.LocalDateTime.now().plusHours(1));
    }

    @Test
    @DisplayName("空 token：匿名放行，不查 token，不写上下文")
    void testNoToken_passesAnonymously() throws Exception {
        MockHttpServletRequest request = request("/admin-api/system/auth/get-permission-info", null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(oauth2TokenApi, never()).checkAccessToken(anyString());
        verify(chain, times(1)).doFilter(any(), any());
        assertNull(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUser());
    }

    @Test
    @DisplayName("伪造/不存在的 token（401 ServiceException）：不终止链路、不写认证上下文")
    void testForgedToken_notAuthenticated_butChainContinues() throws Exception {
        when(oauth2TokenApi.checkAccessToken(VALID_TOKEN))
                .thenThrow(new ServiceException(401, "访问令牌不存在"));
        MockHttpServletRequest request = request("/app-api/member/user/get", VALID_TOKEN);
        request.setAttribute("loginUserType", 2); // app-api → userType=会员

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        // 无需登录的接口（如 /app-api 部分接口）应继续链路，由授权阶段决定结果
        verify(chain, times(1)).doFilter(any(), any());
        assertNull(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUser());
    }

    @Test
    @DisplayName("过期 token（401）：同伪造 token，静默放行")
    void testExpiredToken_notAuthenticated() throws Exception {
        when(oauth2TokenApi.checkAccessToken(VALID_TOKEN))
                .thenThrow(new ServiceException(401, "访问令牌已过期"));
        MockHttpServletRequest request = request("/app-api/member/user/get", VALID_TOKEN);
        request.setAttribute("loginUserType", 2);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertNull(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUser());
    }

    @Test
    @DisplayName("有效 admin token 访问 /admin-api：认证成功，上下文含 userId")
    void testValidToken_authenticated() throws Exception {
        when(oauth2TokenApi.checkAccessToken(VALID_TOKEN)).thenReturn(tokenOf(1L, 2));
        MockHttpServletRequest request = request("/admin-api/system/user/page", VALID_TOKEN);
        request.setAttribute("loginUserType", 2);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        var loginUser = cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUser();
        assertNotNull(loginUser);
        assertEquals(1L, loginUser.getId());
    }

    @Test
    @DisplayName("越权：admin token 访问 /app-api → AccessDeniedException，请求终止不进业务链路")
    void testUserTypeMismatch_accessDenied() throws Exception {
        when(oauth2TokenApi.checkAccessToken(VALID_TOKEN)).thenReturn(tokenOf(1L, 2)); // admin 令牌
        MockHttpServletRequest request = request("/app-api/member/user/get", VALID_TOKEN);
        request.setAttribute("loginUserType", 1); // 期望会员

        MockHttpServletResponse response = new MockHttpServletResponse();
        when(globalExceptionHandler.allExceptionHandler(any(), any()))
                .thenReturn(CommonResult.error(403, "错误的用户类型"));

        filter.doFilter(request, response, chain);

        // 请求被终止：不进入业务链路；异常经全局处理器返回 403
        verify(chain, never()).doFilter(any(), any());
        verify(globalExceptionHandler, times(1)).allExceptionHandler(any(), any());
        assertEquals(200, response.getStatus()); // CommonResult 由响应体承载
    }

    @Test
    @DisplayName("mock 登录关闭：mock token 前缀不产生认证")
    void testMockDisabled_noAuth() throws Exception {
        String mockToken = "mock-user-1";
        when(oauth2TokenApi.checkAccessToken(mockToken))
                .thenThrow(new ServiceException(401, "访问令牌不存在"));
        MockHttpServletRequest request = request("/admin-api/system/user/page", mockToken);
        request.setAttribute("loginUserType", 2);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertNull(cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUser());
    }
}
