package com.spring.gateway.common;

import com.spring.gateway.identity.entity.User;
import com.spring.gateway.identity.service.UserProfileService;
import com.spring.gateway.identity.service.AuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * 会话接口鉴权拦截器。
 *
 * @author hanbing
 * @since 2026-09-26
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /**
     * 存放用户 ID 的请求属性名
     */
    public static final String ATTR_USER_ID = "userId";

    /**
     * 存放用户角色（owner/admin/user）的请求属性名
     */
    public static final String ATTR_USER_ROLE = "userRole";

    /** 管理接口路径前缀：仅站长/管理员可访问 */
    private static final String ADMIN_PATH_PREFIX = "/api/admin/";

    private final AuthSessionService authSessionService;
    private final UserProfileService userProfileService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        AuthSessionService.Authentication authentication = authSessionService.authenticate(token);
        if (authentication.failure() != null) {
            rejectAuthentication(response, authentication.failure());
            return false;
        }
        Long userId = authentication.userId();
        User user = userProfileService.find(userId);
        if (user == null) {
            rejectAuthentication(response, AuthSessionService.TokenFailure.INVALID);
            return false;
        }
        if (User.STATUS_DISABLED.equals(user.getStatus())) {
            reject(response, HttpStatus.FORBIDDEN, "账号已被禁用", null);
            return false;
        }
        if (request.getRequestURI().startsWith(ADMIN_PATH_PREFIX)
                && !User.ROLE_OWNER.equals(user.getRole())
                && !User.ROLE_ADMIN.equals(user.getRole())) {
            reject(response, HttpStatus.FORBIDDEN, "无权限", null);
            return false;
        }
        if (handler instanceof HandlerMethod handlerMethod) {
            RequiresOwner requiresOwner = handlerMethod.getMethodAnnotation(RequiresOwner.class);
            if (requiresOwner != null && !User.ROLE_OWNER.equals(user.getRole())) {
                reject(response, HttpStatus.FORBIDDEN, requiresOwner.message(), null);
                return false;
            }
        }
        request.setAttribute(ATTR_USER_ID, userId);
        request.setAttribute(ATTR_USER_ROLE, user.getRole());
        return true;
    }

    private void rejectAuthentication(HttpServletResponse response, AuthSessionService.TokenFailure failure)
            throws Exception {
        boolean expired = failure == AuthSessionService.TokenFailure.EXPIRED;
        reject(response, HttpStatus.UNAUTHORIZED, expired ? "登录已过期" : "登录状态无效",
                expired ? "token_expired" : "token_invalid");
    }

    private void reject(HttpServletResponse response, HttpStatus status, String message, String authError)
            throws Exception {
        response.setStatus(status.value());
        if (authError != null) {
            response.setHeader(AuthSessionService.AUTH_ERROR_HEADER, authError);
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(status.value(), message)));
    }
}
