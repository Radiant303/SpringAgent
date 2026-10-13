package com.spring.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.entity.PointsHold;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 请求冻结单 Mapper。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Mapper
public interface PointsHoldMapper extends BaseMapper<PointsHold> {
    @Update("UPDATE points_holds SET status = 'STALE' WHERE request_id = #{requestId} AND status = 'HELD'")
    int markStale(@Param("requestId") String requestId);
}
