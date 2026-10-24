package com.spring.gateway.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.search.entity.SearchUsageRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 搜索用量底账 Mapper。
 *
 * @author hanbing
 * @since 2026-10-17
 */
@Mapper
public interface SearchUsageRecordMapper extends BaseMapper<SearchUsageRecord> {
    @Select("SELECT * FROM search_usage_records WHERE event_id = #{eventId} FOR UPDATE")
    SearchUsageRecord selectForUpdate(@Param("eventId") String eventId);
}
