package com.spring.gateway.config;

import com.spring.gateway.common.AuthInterceptor;
import com.spring.gateway.common.InternalAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置。
 *
 * @author hanbing
 * @since 2026-09-26
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final InternalAuthInterceptor internalAuthInterceptor;

    @Value("${spring.web.resources.static-locations:file:../frontend/}")
    private String staticLocations;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/sessions/**", "/api/admin/**", "/api/works/**", "/api/billing/**",
                        "/api/account/**", "/api/files/**", "/api/knowledge/**");
        // 内部接口：持共享静态令牌访问
        registry.addInterceptor(internalAuthInterceptor).addPathPatterns("/internal/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/static/**").addResourceLocations(staticLocations)
                .setCacheControl(org.springframework.http.CacheControl.noCache());
    }
}
