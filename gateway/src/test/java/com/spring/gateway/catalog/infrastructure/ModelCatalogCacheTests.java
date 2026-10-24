package com.spring.gateway.catalog.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 模型目录缓存失效器测试（真实 Redis）。
 *
 * <p>不加 @Transactional：invalidate 在非事务环境走"立即删 + 延迟再删"的直接路径；
 * 事务环境（afterCommit 登记）由 AdminModelConfigServiceTests 的写路径间接覆盖。
 *
 * @author hanbing
 * @since 2026-10-09
 */
@SpringBootTest
class ModelCatalogCacheTests {

    @Autowired
    private ModelCatalogCache cache;
    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("invalidate 立即删除缓存；窗口期回填的旧值被延迟二删清掉")
    void invalidateDeletesImmediatelyAndAgainLater() throws Exception {
        String key = ModelCatalogCache.CACHE_KEY;

        // 第一次删除：立即生效
        redis.opsForValue().set(key, "v1");
        cache.invalidate();
        assertNull(redis.opsForValue().get(key), "invalidate 后缓存应立即被删除");

        // 模拟并发窗口：引擎在第一次删除后把旧快照回填进来；延迟二删应将其清除
        redis.opsForValue().set(key, "stale");
        Thread.sleep(1300);  // 延迟窗口 1s，留 300ms 余量
        assertNull(redis.opsForValue().get(key), "延迟第二次删除应清掉窗口期回填的脏值");
    }
}
