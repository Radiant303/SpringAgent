package com.spring.gateway.ws;

import com.spring.gateway.identity.entity.User;
import com.spring.gateway.identity.service.AuthSessionService;
import com.spring.gateway.identity.service.UserProfileService;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WS 握手鉴权测试。
 *
 * @author hanbing
 * @since 2026-11-12
 */
class AuthHandshakeInterceptorTests {
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final UserProfileService profiles = mock(UserProfileService.class);
    private final AuthHandshakeInterceptor interceptor = new AuthHandshakeInterceptor(sessions, profiles);
    private final Map<String, Object> attributes = new HashMap<>();

    @Test
    void activeUserIsAcceptedWithOneProfileQuery() {
        User user = new User();
        user.setStatus(User.STATUS_ACTIVE);
        when(sessions.authenticate("token")).thenReturn(AuthSessionService.Authentication.valid(123L));
        when(profiles.find(123L)).thenReturn(user);

        assertTrue(handshake("token"));

        verify(profiles).find(123L);
        assertFalse(attributes.containsKey(AuthHandshakeInterceptor.ATTR_USER_MISSING));
        assertFalse(attributes.containsKey(AuthHandshakeInterceptor.ATTR_USER_DISABLED));
    }

    @Test
    void missingAndDisabledUsersAreMarkedForRelayRejection() {
        when(sessions.authenticate("token")).thenReturn(AuthSessionService.Authentication.valid(123L));
        when(profiles.find(123L)).thenReturn(null);
        assertTrue(handshake("token"));
        assertTrue(attributes.containsKey(AuthHandshakeInterceptor.ATTR_USER_MISSING));

        attributes.clear();
        User disabled = new User();
        disabled.setStatus(User.STATUS_DISABLED);
        when(profiles.find(123L)).thenReturn(disabled);
        assertTrue(handshake("token"));
        assertTrue(attributes.containsKey(AuthHandshakeInterceptor.ATTR_USER_DISABLED));
    }

    @Test
    void invalidTokenDoesNotQueryProfile() {
        when(sessions.authenticate("token")).thenReturn(AuthSessionService.Authentication.invalid());

        assertFalse(handshake("token"));

        verifyNoInteractions(profiles);
    }

    private boolean handshake(String token) {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getParameter("token")).thenReturn(token);
        when(servletRequest.getServletContext()).thenReturn(mock(ServletContext.class));
        return interceptor.beforeHandshake(new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(mock(HttpServletResponse.class)), mock(WebSocketHandler.class), attributes);
    }
}
