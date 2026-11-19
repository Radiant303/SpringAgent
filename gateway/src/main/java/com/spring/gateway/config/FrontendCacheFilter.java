package com.spring.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 哈希前端资源的缓存响应过滤器。
 *
 * @author hanbing
 * @since 2026-11-19
 */
@Component
public class FrontendCacheFilter extends OncePerRequestFilter {

    private static final Pattern HASHED_ASSET = Pattern.compile("^/static/.*\\.[0-9a-f]{8}\\.[^/]+$");
    private static final String SEVEN_DAYS = CacheControl.maxAge(Duration.ofDays(7)).cachePublic().getHeaderValue();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!HASHED_ASSET.matcher(request.getRequestURI()).matches()) {
            filterChain.doFilter(request, response);
            return;
        }
        filterChain.doFilter(request, new HttpServletResponseWrapper(response) {
            @Override
            public void setHeader(String name, String value) {
                super.setHeader(name, cacheValue(name, value));
            }

            @Override
            public void addHeader(String name, String value) {
                if (HttpHeaders.CACHE_CONTROL.equalsIgnoreCase(name)) {
                    super.setHeader(name, SEVEN_DAYS);
                    return;
                }
                super.addHeader(name, value);
            }
        });
    }

    private String cacheValue(String name, String value) {
        return HttpHeaders.CACHE_CONTROL.equalsIgnoreCase(name) ? SEVEN_DAYS : value;
    }
}
