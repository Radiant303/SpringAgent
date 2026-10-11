package com.spring.gateway.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 分页参数：page 从 1 开始，size 钳制在 1..100。
 *
 * @author hanbing
 * @since 2026-10-11
 */
public record PageQuery(int page, int size) {

    /** 每页条数上限。 */
    public static final int MAX_SIZE = 100;

    public PageQuery {
        page = Math.max(1, page);
        size = Math.max(1, Math.min(size, MAX_SIZE));
    }

    /**
     * 转成 MyBatis-Plus 分页对象
     *
     * @param <T> 行类型
     * @return 分页对象
     */
    public <T> Page<T> toPage() {
        return new Page<>(page, size);
    }
}
