package com.spring.gateway.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.billing.entity.PointsLedger;
import org.apache.ibatis.annotations.Mapper;

/**
 * points_ledger 表的 Mapper。
 *
 * @author hanbing
 * @since 2026-10-05
 */
@Mapper
public interface PointsLedgerMapper extends BaseMapper<PointsLedger> {
}
