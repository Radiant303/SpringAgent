package com.spring.gateway.config;

import jakarta.servlet.ServletContext;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WS 容器缓冲配置。
 *
 * @author hanbing
 * @since 2026-10-02
 */
@Configuration
public class WebSocketContainerConfig {

    /** WS 单帧大小上限：16MB */
    private static final int MAX_WS_MESSAGE_BYTES = 16 * 1024 * 1024;

    /** 服务端 WS 容器在 ServletContext 中的属性名（jakarta.websocket 规范定义） */
    private static final String SERVER_CONTAINER_ATTR = "jakarta.websocket.server.ServerContainer";

    @Bean
    public WebSocketContainer webSocketContainer() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(MAX_WS_MESSAGE_BYTES);
        container.setDefaultMaxBinaryMessageBufferSize(MAX_WS_MESSAGE_BYTES);
        return container;
    }

    /**
     * 调大服务端方向的单帧上限。
     *
     * @return true = 已生效；false = ServerContainer 尚未就绪
     */
    public static boolean enlargeServerBuffer(ServletContext servletContext) {
        if (servletContext.getAttribute(SERVER_CONTAINER_ATTR)
                instanceof WebSocketContainer serverContainer) {
            serverContainer.setDefaultMaxTextMessageBufferSize(MAX_WS_MESSAGE_BYTES);
            serverContainer.setDefaultMaxBinaryMessageBufferSize(MAX_WS_MESSAGE_BYTES);
            return true;
        }
        return false;
    }
}
