package com.spring.gateway.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * REST 请求计时过滤器。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ServerTimingFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return "/ws".equals(path) || path.startsWith("/static/");
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RequestTimings previous = RequestTimings.current();
        RequestTimings timings = (RequestTimings) request.getAttribute(RequestTimings.ATTRIBUTE);
        if (timings == null) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            timings = new RequestTimings(path.equals("/api") || path.startsWith("/api/")
                    || path.equals("/internal") || path.startsWith("/internal/"));
            request.setAttribute(RequestTimings.ATTRIBUTE, timings);
        }
        RequestTimings.bind(timings);
        TimingResponseWrapper wrapped = new TimingResponseWrapper(response, timings);
        try {
            chain.doFilter(request, wrapped);
        } finally {
            try {
                if (!request.isAsyncStarted()) {
                    wrapped.prepare();
                }
            } finally {
                RequestTimings.bind(previous);
            }
        }
    }
}
