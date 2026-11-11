package com.spring.gateway.redemption.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spring.gateway.redemption.entity.RedeemCode;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * redeem_codes 表的 Mapper。
 *
 * @author hanbing
 * @since 2026-10-06
 */
@Mapper
public interface RedeemCodeMapper extends BaseMapper<RedeemCode> {

    @Insert("""
            <script>
            INSERT INTO redeem_codes (id, code, status, points_amount, storage_delta_bytes,
                work_quota_delta_bytes, expires_at, created_by)
            VALUES
            <foreach collection="rows" item="row" separator=",">
                (#{row.id}, #{row.code}, #{row.status}, #{row.pointsAmount}, #{row.storageDeltaBytes},
                #{row.workQuotaDeltaBytes}, #{row.expiresAt}, #{row.createdBy})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<RedeemCode> rows);

    /**
     * 兑换抢占：ACTIVE 且未过期的码原子地转为 REDEEMED，并置兑换人/兑换时间/软删除。
     *
     * @param code   规范形兑换码（大写、无连字符）
     * @param userId 兑换人用户 ID
     * @return 受影响行数（1 = 抢占成功）
     */
    @Update("UPDATE redeem_codes SET status = 'REDEEMED', redeemed_by = #{userId},"
            + " redeemed_at = NOW(6), deleted_at = NOW(6)"
            + " WHERE code = #{code} AND status = 'ACTIVE'"
            + " AND (expires_at IS NULL OR expires_at > NOW(6))")
    int claim(@Param("code") String code, @Param("userId") Long userId);

    /**
     * 作废：仅 ACTIVE 的码可作废（已兑换/已作废不受影响）。
     *
     * @param id 码 ID
     * @return 受影响行数（1 = 作废成功）
     */
    @Update("UPDATE redeem_codes SET status = 'REVOKED' WHERE id = #{id} AND status = 'ACTIVE'")
    int revoke(@Param("id") Long id);
}
