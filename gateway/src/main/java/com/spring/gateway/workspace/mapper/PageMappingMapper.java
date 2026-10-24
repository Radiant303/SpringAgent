package com.spring.gateway.workspace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.workspace.entity.PageMapping;
import org.apache.ibatis.annotations.Mapper;

/**
 * page_mappings 表的 Mapper。
 *
 * @author hanbing
 * @since 2026-10-16
 */
@Mapper
public interface PageMappingMapper extends BaseMapper<PageMapping> {
}
