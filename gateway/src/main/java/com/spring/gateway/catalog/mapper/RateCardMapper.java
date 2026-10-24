package com.spring.gateway.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.catalog.entity.RateCard;
import org.apache.ibatis.annotations.Mapper;

/**
 * models 表（模型资费卡）的 Mapper。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Mapper
public interface RateCardMapper extends BaseMapper<RateCard> {
}
