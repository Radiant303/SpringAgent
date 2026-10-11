package com.spring.gateway.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * 分页返回体。
 *
 * @author hanbing
 * @since 2026-10-11
 * @param list  当前页数据
 * @param total 总条数
 * @param page  当前页码（1 起）
 * @param size  每页条数
 * @param <T>   行类型
 */
public record PageResult<T>(List<T> list, long total, long page, long size) {

    /**
     * 从 MyBatis-Plus 分页结果转换
     *
     * @param p 分页结果
     * @param <T> 行类型
     * @return 分页返回体
     */
    public static <T> PageResult<T> of(IPage<T> p) {
        return new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    /**
     * 逐行映射为新类型
     *
     * @param mapper 行映射函数
     * @param <R>    目标类型
     * @return 映射后的分页返回体
     */
    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(list.stream().map(mapper).toList(), total, page, size);
    }
}
