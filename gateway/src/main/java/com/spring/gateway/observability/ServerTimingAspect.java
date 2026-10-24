package com.spring.gateway.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 请求处理关键调用的计时切面。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ServerTimingAspect {
    @Around("@within(org.springframework.web.bind.annotation.RestController) && execution(public * *(..))")
    public Object controller(ProceedingJoinPoint call) throws Throwable {
        return time("controller", call);
    }

    @Around("execution(public * com.spring.gateway..service.impl..*(..))")
    public Object service(ProceedingJoinPoint call) throws Throwable {
        return time("service", call);
    }

    @Around("execution(* com.spring.gateway.common.AuthInterceptor.preHandle(..))"
            + " || execution(* com.spring.gateway.common.InternalAuthInterceptor.preHandle(..))")
    public Object auth(ProceedingJoinPoint call) throws Throwable {
        return time("auth", call);
    }

    @Around("execution(public * com.spring.gateway.engine.infrastructure.HttpEngineClient.*(..))"
            + " || execution(public * com.spring.gateway.catalog.infrastructure.HttpProviderModelsClient.*(..))")
    public Object upstream(ProceedingJoinPoint call) throws Throwable {
        return time("upstream", call);
    }

    @Around("execution(public * com.spring.gateway.storage.infrastructure.*StorageService.*(..))")
    public Object storage(ProceedingJoinPoint call) throws Throwable {
        return time("storage", call);
    }

    private Object time(String stage, ProceedingJoinPoint call) throws Throwable {
        if (RequestTimings.current() == null) {
            return call.proceed();
        }
        String description = call.getSignature().getDeclaringType().getSimpleName()
                + "." + call.getSignature().getName();
        if (description.length() > 72) {
            description = description.substring(0, 72);
        }
        try (RequestTimings.Span ignored = RequestTimings.start(stage, description)) {
            return call.proceed();
        }
    }
}
