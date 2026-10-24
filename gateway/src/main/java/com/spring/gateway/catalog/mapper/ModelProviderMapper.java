package com.spring.gateway.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.catalog.entity.ModelProvider;
import org.apache.ibatis.annotations.Mapper;

/**
 * model_providers 表的 Mapper。
 *
 * @author hanbing
 * @since 2026-10-09
 */
@Mapper
public interface ModelProviderMapper extends BaseMapper<ModelProvider> {
}
