package com.spring.gateway.observability;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Callable;

/**
 * REST 计时的 MVC 配置。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@Configuration
public class ServerTimingWebConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (handler instanceof HandlerMethod method
                        && (AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RestController.class)
                        || method.hasMethodAnnotation(ResponseBody.class)
                        || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), ResponseBody.class))) {
                    RequestTimings timings = (RequestTimings) request.getAttribute(RequestTimings.ATTRIBUTE);
                    if (timings != null) {
                        timings.enable();
                    }
                }
                return true;
            }
        }).order(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.registerCallableInterceptors(new CallableProcessingInterceptor() {
            @Override
            public <T> void preProcess(NativeWebRequest request, Callable<T> task) {
                RequestTimings.bind((RequestTimings) request.getAttribute(
                        RequestTimings.ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST));
            }

            @Override
            public <T> void postProcess(NativeWebRequest request, Callable<T> task, Object result) {
                RequestTimings.bind(null);
            }
        });
    }
}
