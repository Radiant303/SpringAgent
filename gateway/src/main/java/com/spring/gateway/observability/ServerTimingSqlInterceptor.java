package com.spring.gateway.observability;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

/**
 * MyBatis 查询与更新计时插件。
 *
 * @author hanbing
 * @since 2026-10-24
 */
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "queryCursor", args = {
                MappedStatement.class, Object.class, RowBounds.class}),
        @Signature(type = Executor.class, method = "flushStatements", args = {}),
        @Signature(type = Executor.class, method = "query", args = {
                MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query", args = {
                MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                CacheKey.class, BoundSql.class})
})
public class ServerTimingSqlInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (RequestTimings.current() == null) {
            return invocation.proceed();
        }
        try (RequestTimings.Span ignored = RequestTimings.start("db", "MyBatis " + invocation.getMethod().getName())) {
            return invocation.proceed();
        }
    }
}
