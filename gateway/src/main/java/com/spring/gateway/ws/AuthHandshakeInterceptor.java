package com.spring.gateway.ws;

import com.spring.gateway.config.WebSocketContainerConfig;
import com.spring.gateway.identity.service.UserProfileService;
import com.spring.gateway.identity.service.AuthSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WS 握手鉴权。
 *
 * @author hanbing
 * @since 2026-10-02
 */
@Component
@RequiredArgsConstructor
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    /** 用户 ID 会话属性。 */
    public static final String ATTR_USER_ID = "userId";
    /** 访问令牌会话属性。 */
    public static final String ATTR_TOKEN = "token";
    /** 用户不存在标记。 */
    public static final String ATTR_USER_MISSING = "authUserMissing";

    private final AuthSessionService authSessionService;
    private final UserProfileService userProfileService;

    private static final AtomicBoolean SERVER_BUFFER_ENLARGED = new AtomicBoolean();

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            if (!SERVER_BUFFER_ENLARGED.get()) {
                boolean done = WebSocketContainerConfig.enlargeServerBuffer(
                        servletRequest.getServletRequest().getServletContext());
                SERVER_BUFFER_ENLARGED.compareAndSet(false, done);
            }
            String token = servletRequest.getServletRequest().getParameter("token");
            AuthSessionService.Authentication authentication = authSessionService.authenticate(token);
            if (authentication.valid()) {
                Long userId = authentication.userId();
                attributes.put(ATTR_USER_ID, userId);
                attributes.put(ATTR_TOKEN, token);
                if (userProfileService.find(userId) == null) {
                    attributes.put(ATTR_USER_MISSING, Boolean.TRUE);
                }
                return true;
            }
            response.getHeaders().set(AuthSessionService.AUTH_ERROR_HEADER,
                    authentication.failure() == AuthSessionService.TokenFailure.EXPIRED
                            ? "token_expired" : "token_invalid");
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需后置处理
    }
}
